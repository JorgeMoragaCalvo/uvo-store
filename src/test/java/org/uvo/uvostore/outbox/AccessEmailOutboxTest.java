package org.uvo.uvostore.outbox;

import jakarta.mail.SendFailedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.mail.MailSendException;
import org.uvo.uvostore.controller.auth.AuthController;
import org.uvo.uvostore.controller.auth.ForgotPasswordRequest;
import org.uvo.uvostore.entity.outbox.OutboxMessage;
import org.uvo.uvostore.entity.outbox.enums.OutboxChannel;
import org.uvo.uvostore.entity.outbox.enums.OutboxStatus;
import org.uvo.uvostore.entity.outbox.enums.OutboxType;
import org.uvo.uvostore.entity.security.User;
import org.uvo.uvostore.entity.tenant.Store;
import org.uvo.uvostore.security.TenantContext;
import org.uvo.uvostore.service.notification.EmailService;
import org.uvo.uvostore.service.outbox.AccessTokenHash;
import org.uvo.uvostore.service.outbox.OutboxAdminService;
import org.uvo.uvostore.service.outbox.OutboxDispatcher;
import org.uvo.uvostore.service.outbox.OutboxFailureDto;
import org.uvo.uvostore.service.platform.StoreLifecycleService;
import org.uvo.uvostore.service.security.UserCreateCommand;
import org.uvo.uvostore.service.security.UserService;
import org.uvo.uvostore.support.AuditRows;
import org.uvo.uvostore.support.InterruptionTestSupport;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * IMP-06 (C11). Los correos de acceso del panel salen por el outbox: la fila se escribe con el token, se entrega aunque
 * el proceso caiga o el SMTP no responda, y nunca lleva un enlace que ya no vale.
 *
 * <p>Con commit real y el despachador limitado a la tienda de la prueba ({@link InterruptionTestSupport}): la base es
 * la local y no hay que tocar las filas de otras tiendas. El SMTP es el {@code EmailService} simulado de esa base;
 * {@code deliver} es lo que usa el outbox.
 */
class AccessEmailOutboxTest extends InterruptionTestSupport {

    @Autowired
    private AuthController authController;
    @Autowired
    private UserService userService;
    @Autowired
    private StoreLifecycleService lifecycle;
    @Autowired
    private OutboxAdminService outboxAdminService;

    private Store store;
    private User owner;
    private OutboxDispatcher dispatcher;

    @BeforeEach
    void setUpStore() {
        store = fixtures.store("imp06");
        createdStores.add(store.getId());
        owner = fixtures.admin(store, "imp06-owner");
        jdbc.update("UPDATE stores SET owner_user_id = ? WHERE id = ?", owner.getId(), store.getId());
        dispatcher = dispatcher(store.getId());
        reset(emailService);
        when(emailService.deliver(anyString(), anyString(), anyString())).thenReturn(EmailService.Delivery.SENT);
    }

    // --- los cinco flujos encolan -----------------------------------------------------------------------------------

    @Test
    @DisplayName("Alta, reenvío, alta de tienda, recuperación y reset: una fila cada uno, con la huella y sin el token")
    void everyAccessFlowQueuesOneRowWithTheHashOnly() {
        // Alta con invitación y reenvío (el reenvío espera el intervalo mínimo: se adelanta la fecha de envío).
        User invited = within(() -> userService.createUser(new UserCreateCommand(
                "Invitada", "imp06-invitada-" + System.nanoTime() + "@test.local", null, null, true, true, null,
                true, null, Set.of())));
        jdbc.update("UPDATE users SET invitation_sent_at = now() - interval '1 hour' WHERE id = ?", invited.getId());
        TenantContext.runWithin(store, () -> userService.resendInvitation(invited.getId()));
        // Recuperación de acceso del dueño (la del alta de tienda la cubren StoreOnboardingCommitTest y
        // PosStoreOnboardingTest, que no pueden compartir este contexto) y su reset de contraseña.
        lifecycle.recoverOwnerAccess(store.getId(), null);
        forgotPassword(owner);

        List<OutboxMessage> invitedRows = outboxRepository.findByUserIdOrderByIdDesc(invited.getId());
        assertThat(invitedRows).hasSize(2).allSatisfy(row -> {
            assertThat(row.getType()).isEqualTo(OutboxType.ADMIN_INVITATION_EMAIL);
            assertThat(row.getOrder()).isNull();
        });
        assertThat(invitedRows.getFirst().getTokenHash())
                .isEqualTo(AccessTokenHash.of(currentInvitationToken(invited)));
        assertThat(outboxRepository.findByUserIdOrderByIdDesc(owner.getId()))
                .extracting(OutboxMessage::getType)
                .containsExactly(OutboxType.PASSWORD_RESET_EMAIL, OutboxType.ADMIN_INVITATION_EMAIL);
        // Ninguna columna de ninguna fila de la tienda lleva un token vivo.
        for (String token : List.of(currentInvitationToken(invited), currentInvitationToken(owner),
                currentResetToken(owner))) {
            assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_messages WHERE store_id = ? AND "
                            + "(dedup_key LIKE ? OR token_hash = ? OR coalesce(last_error, '') LIKE ?)", Integer.class,
                    store.getId(), "%" + token + "%", token, "%" + token + "%")).isZero();
        }
        // Y nada salió por el SMTP durante las peticiones: eso es del despachador.
        verify(emailService, never()).deliver(anyString(), anyString(), anyString());
        verify(emailService, never()).send(anyString(), anyString(), anyString());
    }

    // --- entrega ----------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("Caída después del commit: la siguiente pasada entrega el enlace del token vigente, una vez")
    void aRowLeftByACrashIsDeliveredOnceWithTheCurrentLink() {
        // El commit del reset ocurrió y el proceso murió antes de despachar: la fila está PENDING con 0 intentos.
        forgotPassword(owner);
        OutboxMessage row = onlyRow(owner);
        assertThat(row.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(row.getAttempts()).isZero();

        // "Reinicio": un despachador nuevo sobre lo que quedó en la base.
        dispatcher(store.getId()).dispatch(OutboxChannel.ACCESS);
        dispatcher.dispatch(OutboxChannel.ACCESS);

        String body = deliveredBody(1);
        assertThat(body).contains("/admin/reset-password?token=" + currentResetToken(owner));
        assertThat(reload(row).getStatus()).isEqualTo(OutboxStatus.SENT);
    }

    @Test
    @DisplayName("Un reset reemplazado: la fila vieja se omite y solo sale el enlace nuevo")
    void aReplacedResetSkipsTheOldRow() {
        forgotPassword(owner);
        forgotPassword(owner);
        List<OutboxMessage> rows = outboxRepository.findByUserIdOrderByIdDesc(owner.getId());
        assertThat(rows).hasSize(2);

        dispatcher.dispatch(OutboxChannel.ACCESS);

        OutboxMessage newer = reload(rows.get(0));
        OutboxMessage older = reload(rows.get(1));
        assertThat(older.getStatus()).isEqualTo(OutboxStatus.SKIPPED);
        assertThat(older.getLastError()).startsWith("Reemplazado por un envío posterior");
        assertThat(newer.getStatus()).isEqualTo(OutboxStatus.SENT);
        assertThat(deliveredBody(1)).contains("token=" + currentResetToken(owner));
    }

    @Test
    @DisplayName("Una invitación ya aceptada no genera un enlace: la fila se omite")
    void aConsumedInvitationIsSkipped() {
        lifecycle.recoverOwnerAccess(store.getId(), null);
        // Lo que deja adminAcceptInvitation (AdminInvitationTest lo recorre por HTTP): contraseña, sin token.
        jdbc.update("UPDATE users SET invitation_token = NULL, invitation_accepted_at = now(), password = 'x' "
                + "WHERE id = ?", owner.getId());

        dispatcher.dispatch(OutboxChannel.ACCESS);

        OutboxMessage row = reload(onlyRow(owner));
        assertThat(row.getStatus()).isEqualTo(OutboxStatus.SKIPPED);
        assertThat(row.getLastError()).isEqualTo("La invitación ya se usó o se cerró");
        verify(emailService, never()).deliver(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("Un enlace vencido mientras esperaba no sale")
    void anExpiredLinkIsSkipped() {
        forgotPassword(owner);
        jdbc.update("UPDATE users SET password_reset_expires_at = now() - interval '1 minute' WHERE id = ?",
                owner.getId());

        dispatcher.dispatch(OutboxChannel.ACCESS);

        OutboxMessage row = reload(onlyRow(owner));
        assertThat(row.getStatus()).isEqualTo(OutboxStatus.SKIPPED);
        assertThat(row.getLastError()).startsWith("El enlace venció");
        verify(emailService, never()).deliver(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("SMTP fuera de servicio: queda en cola con el error, y al volver sale el mismo enlace")
    void anSmtpOutageIsRetriedWithTheSameLink() {
        forgotPassword(owner);
        when(emailService.deliver(anyString(), anyString(), anyString()))
                .thenThrow(new MailSendException("Mail server connection failed"));

        dispatcher.dispatch(OutboxChannel.ACCESS);

        OutboxMessage waiting = reload(onlyRow(owner));
        assertThat(waiting.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(waiting.getAttempts()).isEqualTo(1);
        assertThat(waiting.getLastError()).startsWith("Fallo de envío");
        assertThat(waiting.getNextAttemptAt()).isAfter(Instant.now());

        // Vuelve el SMTP y llega la hora del reintento (se adelanta en vez de esperar el minuto de espera; un segundo
        // antes, porque el reloj de PostgreSQL y el de la JVM no coinciden al microsegundo).
        reset(emailService);
        when(emailService.deliver(anyString(), anyString(), anyString())).thenReturn(EmailService.Delivery.SENT);
        jdbc.update("UPDATE outbox_messages SET next_attempt_at = now() - interval '1 second' WHERE id = ?", waiting.getId());
        dispatcher(store.getId()).dispatch(OutboxChannel.ACCESS);

        assertThat(reload(waiting).getStatus()).isEqualTo(OutboxStatus.SENT);
        assertThat(deliveredBody(1)).contains("token=" + currentResetToken(owner));
    }

    @Test
    @DisplayName("Huérfana en vuelo (el proceso murió enviando): se reintenta con el mismo enlace de un solo uso")
    void anOrphanInFlightIsRetriedWithTheSameLink() {
        forgotPassword(owner);
        OutboxMessage row = onlyRow(owner);
        jdbc.update("UPDATE outbox_messages SET status = 'IN_FLIGHT', attempts = 1, updated_at = ? WHERE id = ?",
                Timestamp.from(Instant.now().minus(Duration.ofMinutes(10))), row.getId());

        dispatcher.dispatch(OutboxChannel.ACCESS);

        OutboxMessage recovered = reload(row);
        assertThat(recovered.getStatus()).as("un correo incierto se reintenta, no se abandona")
                .isEqualTo(OutboxStatus.PENDING);
        assertThat(recovered.getLastError()).startsWith("Huérfana en vuelo");

        jdbc.update("UPDATE outbox_messages SET next_attempt_at = now() - interval '1 second' WHERE id = ?", row.getId());
        dispatcher.dispatch(OutboxChannel.ACCESS);

        assertThat(reload(row).getStatus()).isEqualTo(OutboxStatus.SENT);
        assertThat(deliveredBody(1)).contains("token=" + currentResetToken(owner));
    }

    @Test
    @DisplayName("Una dirección rechazada falla de inmediato y se ve en el panel y en la plataforma")
    void aRejectedAddressFailsAndIsVisible() {
        forgotPassword(owner);
        when(emailService.deliver(anyString(), anyString(), anyString())).thenThrow(new MailSendException(
                "rechazada", null, Map.of(new Object(), new SendFailedException("550 No such user"))));

        dispatcher.dispatch(OutboxChannel.ACCESS);

        OutboxMessage failed = reload(onlyRow(owner));
        assertThat(failed.getStatus()).isEqualTo(OutboxStatus.FAILED);
        assertThat(failed.getLastError()).startsWith("Rechazado: El servidor rechazó la dirección");

        List<OutboxFailureDto> panel = within(
                () -> outboxAdminService.listFailed(PageRequest.of(0, 20)).getContent());
        assertThat(panel).singleElement().satisfies(dto -> {
            assertThat(dto.type()).isEqualTo(OutboxType.PASSWORD_RESET_EMAIL);
            assertThat(dto.channel()).isEqualTo(OutboxChannel.ACCESS);
            assertThat(dto.orderId()).isNull();
            assertThat(dto.userId()).isEqualTo(owner.getId());
            assertThat(dto.recipientEmail()).isEqualTo(owner.getEmail());
        });
        assertThat(outboxAdminService.listFailedAccessEmails(store.getId(), PageRequest.of(0, 20)).getContent())
                .extracting(OutboxFailureDto::id).containsExactly(failed.getId());
    }

    @Test
    @DisplayName("Agotado el plazo con el SMTP caído: FAILED; reintentado desde la plataforma, se entrega y queda auditado")
    void aGivenUpRowIsRecoveredFromThePlatform() {
        lifecycle.recoverOwnerAccess(store.getId(), null);
        OutboxMessage row = onlyRow(owner);
        // Lleva más de las 48 h de app.outbox.give-up-after-hours en la cola.
        jdbc.update("UPDATE outbox_messages SET created_at = now() - interval '49 hours' WHERE id = ?", row.getId());
        when(emailService.deliver(anyString(), anyString(), anyString()))
                .thenThrow(new MailSendException("Mail server connection failed"));

        dispatcher.dispatch(OutboxChannel.ACCESS);

        assertThat(reload(row).getStatus()).isEqualTo(OutboxStatus.FAILED);
        assertThat(reload(row).getLastError()).startsWith("Sin éxito tras 48 h");

        reset(emailService);
        when(emailService.deliver(anyString(), anyString(), anyString())).thenReturn(EmailService.Delivery.SENT);
        outboxAdminService.retryAccessEmail(row.getId(), "El SMTP volvió");
        dispatcher.dispatch(OutboxChannel.ACCESS);

        assertThat(reload(row).getStatus()).isEqualTo(OutboxStatus.SENT);
        assertThat(deliveredBody(1)).contains("/admin/aceptar-invitacion?token=" + currentInvitationToken(owner));
        AuditRows.Row audit = AuditRows.single(jdbc, store.getId(), "OUTBOX_RETRY");
        assertThat(audit.targetType()).isEqualTo("USER");
        assertThat(audit.targetId()).isEqualTo(owner.getId());
        assertThat(audit.details()).doesNotContain(currentInvitationToken(owner)).doesNotContain(row.getTokenHash());
    }

    // --- apoyo ------------------------------------------------------------------------------------------------------

    private void forgotPassword(User user) {
        TenantContext.runWithin(store, () -> authController.adminForgotPassword(new ForgotPasswordRequest(user.getEmail())));
    }

    private <T> T within(Supplier<T> action) {
        AtomicReference<T> result = new AtomicReference<>();
        TenantContext.runWithin(store, () -> result.set(action.get()));
        return result.get();
    }

    private OutboxMessage onlyRow(User user) {
        List<OutboxMessage> rows = outboxRepository.findByUserIdOrderByIdDesc(user.getId());
        assertThat(rows).hasSize(1);
        return rows.getFirst();
    }

    private OutboxMessage reload(OutboxMessage row) {
        return outboxRepository.findById(row.getId()).orElseThrow();
    }

    private String currentResetToken(User user) {
        return jdbc.queryForObject("SELECT password_reset_token FROM users WHERE id = ?", String.class, user.getId());
    }

    private String currentInvitationToken(User user) {
        return jdbc.queryForObject("SELECT invitation_token FROM users WHERE id = ?", String.class, user.getId());
    }

    /** El cuerpo del único correo entregado, comprobando que salió exactamente {@code times} veces. */
    private String deliveredBody(int times) {
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(emailService, times(times)).deliver(eq(owner.getEmail()), anyString(), body.capture());
        return body.getValue();
    }
}
