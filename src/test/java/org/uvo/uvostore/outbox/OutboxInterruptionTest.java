package org.uvo.uvostore.outbox;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.uvo.uvostore.entity.order.Order;
import org.uvo.uvostore.entity.outbox.OutboxMessage;
import org.uvo.uvostore.entity.outbox.enums.OutboxChannel;
import org.uvo.uvostore.entity.outbox.enums.OutboxStatus;
import org.uvo.uvostore.entity.outbox.enums.OutboxType;
import org.uvo.uvostore.security.TenantContext;
import org.uvo.uvostore.service.BusinessException;
import org.uvo.uvostore.service.notification.EmailService;
import org.uvo.uvostore.service.outbox.OutboxAdminService;
import org.uvo.uvostore.service.pos.PosDelivery;
import org.uvo.uvostore.service.pos.PosOrderPayload;
import org.uvo.uvostore.service.pos.PosOrderPayloadMapper;
import org.uvo.uvostore.support.AuditRows;
import org.uvo.uvostore.support.InterruptionTestSupport;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * GAP-04. Una entrega del outbox cortada antes y después de cada uno de sus puntos —el reclamo (commit), la llamada al
 * POS o al SMTP, el cierre (commit)— y lo que pasa al volver, incluido el reintento desde el panel.
 *
 * <p>La regla que se comprueba: una venta al POS de la que no se sabe si llegó no se reenvía sola (POS-1: el POS no
 * confirmó que respete la clave de idempotencia), y solo vuelve a salir cuando una persona lo pide desde el panel,
 * con la misma clave. Un correo incierto sí se reintenta: duplicarlo es una molestia, no un documento tributario.
 * {@code OutboxCrashRecoveryTest} cubre las filas sin intentar y las que quedaron en vuelo sin pasar por aquí.
 */
class OutboxInterruptionTest extends InterruptionTestSupport {

    private static final Duration ORPHAN_AGE = Duration.ofMinutes(10);

    @Autowired
    private OutboxAdminService outboxAdminService;

    @Test
    @DisplayName("Se cayó tras el reclamo y antes de llamar: la venta queda para revisión y el correo sale una vez")
    void diedAfterTheClaimAndBeforeTheCall() throws Exception {
        Shop shop = shop(10);
        Order order = pay(shop, pendingWebpayOrder(shop, 1, null));
        OutboxMessage sale = row(order, OutboxType.POS_ORDER);
        OutboxMessage mail = row(order, OutboxType.ORDER_CONFIRMATION_EMAIL);
        when(emailService.deliver(any(), any(), any())).thenReturn(EmailService.Delivery.SENT);

        // El reclamo se confirmó (IN_FLIGHT, intento contado) y el proceso murió antes de la llamada.
        assertThat(outboxStore.claim(sale.getId(), Instant.now())).isTrue();
        assertThat(outboxStore.claim(mail.getId(), Instant.now())).isTrue();
        age(sale, ORPHAN_AGE);
        age(mail, ORPHAN_AGE);

        dispatcher(shop).dispatch(OutboxChannel.POS);
        dispatcher(shop).dispatch(OutboxChannel.MAIL);
        makeDue(mail);
        dispatcher(shop).dispatch(OutboxChannel.MAIL);
        dispatcher(shop).dispatch(OutboxChannel.MAIL);

        verify(posClient, never()).notifyOrder(any(), any());
        assertThat(reload(sale).getStatus()).isEqualTo(OutboxStatus.FAILED);
        assertThat(reload(sale).getLastError()).contains("Huérfana en vuelo");
        verify(emailService, times(1)).deliver(any(), any(), any());
        assertThat(reload(mail).getStatus()).isEqualTo(OutboxStatus.SENT);
    }

    @Test
    @DisplayName("La venta llegó y se cayó antes de cerrarla: no se reenvía sola, y el reintento del panel usa la misma clave")
    void aSaleDeliveredButNotClosedWaitsForAPerson() throws Exception {
        Shop shop = shop(10);
        Order order = pay(shop, pendingWebpayOrder(shop, 1, null));
        OutboxMessage sale = row(order, OutboxType.POS_ORDER);
        when(posClient.notifyOrder(any(), any())).thenReturn(PosDelivery.sent(Map.of()));
        doThrow(new IllegalStateException("Caída simulada antes de cerrar el intento"))
                .doCallRealMethod()
                .when(outboxStore).finish(eq(sale.getId()), anyInt(), any(), any(), any());

        dispatcher(shop).dispatch(OutboxChannel.POS);

        verify(posClient, times(1)).notifyOrder(any(), any());
        assertThat(reload(sale).getStatus()).isEqualTo(OutboxStatus.IN_FLIGHT);

        // Al volver: la fila es huérfana y no se sabe si llegó. No se reenvía.
        age(sale, ORPHAN_AGE);
        dispatcher(shop).dispatch(OutboxChannel.POS);
        dispatcher(shop).dispatch(OutboxChannel.POS);

        verify(posClient, times(1)).notifyOrder(any(), any());
        OutboxMessage failed = reload(sale);
        assertThat(failed.getStatus()).isEqualTo(OutboxStatus.FAILED);
        assertThat(failed.getAttempts()).isEqualTo(1);

        // Sin confirmar la revisión del POS, el panel no la reintenta.
        assertThatThrownBy(() -> TenantContext.runWithin(shop.store(),
                () -> outboxAdminService.retry(sale.getId(), "Reintento", false)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("pudo haber llegado al POS");
        assertThat(reload(sale).getStatus()).isEqualTo(OutboxStatus.FAILED);

        TenantContext.runWithin(shop.store(),
                () -> outboxAdminService.retry(sale.getId(), "Revisado en el POS: la venta no está", true));
        dispatcher(shop).dispatch(OutboxChannel.POS);
        dispatcher(shop).dispatch(OutboxChannel.POS);

        ArgumentCaptor<PosOrderPayload> payloads = ArgumentCaptor.forClass(PosOrderPayload.class);
        verify(posClient, times(2)).notifyOrder(any(), payloads.capture());
        assertThat(payloads.getAllValues()).extracting(PosOrderPayload::idempotencyKey)
                .containsOnly(PosOrderPayloadMapper.idempotencyKey(order.getOrderNumber(), shop.companyId()));
        OutboxMessage sent = reload(sale);
        assertThat(sent.getStatus()).isEqualTo(OutboxStatus.SENT);
        assertThat(sent.getAttempts()).isEqualTo(2);
        assertThat(notes(order)).anyMatch(note -> note.startsWith("Venta al POS: reintento pedido desde el panel")
                && note.endsWith("la venta no está"));
        assertThat(AuditRows.single(jdbc, shop.store().getId(), "OUTBOX_RETRY").details())
                .contains("\"posChecked\"").contains("true");
    }

    @Test
    @DisplayName("El correo salió y se cayó antes de cerrarlo: se reenvía una vez, y es el duplicado que se acepta")
    void anEmailDeliveredButNotClosedIsSentOnceMore() throws Exception {
        Shop shop = shop(10);
        Order order = pay(shop, pendingWebpayOrder(shop, 1, null));
        OutboxMessage mail = row(order, OutboxType.ORDER_CONFIRMATION_EMAIL);
        when(emailService.deliver(any(), any(), any())).thenReturn(EmailService.Delivery.SENT);
        doThrow(new IllegalStateException("Caída simulada antes de cerrar el intento"))
                .doCallRealMethod()
                .when(outboxStore).finish(eq(mail.getId()), anyInt(), any(), any(), any());

        dispatcher(shop).dispatch(OutboxChannel.MAIL);
        age(mail, ORPHAN_AGE);
        dispatcher(shop).dispatch(OutboxChannel.MAIL);
        makeDue(mail);
        dispatcher(shop).dispatch(OutboxChannel.MAIL);
        dispatcher(shop).dispatch(OutboxChannel.MAIL);

        verify(emailService, times(2)).deliver(any(), any(), any());
        assertThat(reload(mail).getStatus()).isEqualTo(OutboxStatus.SENT);
    }

    @Test
    @DisplayName("Una fila de hace días reintentada desde el panel tiene sus horas de reintento, no vuelve a FAILED al primer fallo")
    void anOldRowRequeuedGetsAFreshRetryWindow() throws Exception {
        Shop shop = shop(10);
        Order order = pay(shop, pendingWebpayOrder(shop, 1, null));
        OutboxMessage sale = row(order, OutboxType.POS_ORDER);
        // Rechazada por el POS hace tres días (por ejemplo, un producto sin mapear que ya se corrigió).
        jdbc.update("UPDATE outbox_messages SET status = 'FAILED', attempts = 1, last_error = ?, "
                        + "created_at = now() - interval '3 days', updated_at = now() - interval '3 days' WHERE id = ?",
                "Rechazado: producto sin mapeo", sale.getId());

        // Rechazada no es incierta: no hace falta confirmar la revisión del POS.
        TenantContext.runWithin(shop.store(),
                () -> outboxAdminService.retry(sale.getId(), "Producto mapeado", false));
        when(posClient.notifyOrder(any(), any())).thenReturn(PosDelivery.notSent("Conexión rechazada"));
        dispatcher(shop).dispatch(OutboxChannel.POS);

        OutboxMessage retried = reload(sale);
        assertThat(retried.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(retried.getAttempts()).isEqualTo(2);
        assertThat(retried.getRequeuedAt()).isNotNull();
        assertThat(retried.getNextAttemptAt()).isAfter(Instant.now());
    }

    @Test
    @DisplayName("Una venta heredada de antes del outbox no se reintenta: solo se marca resuelta, con nota y auditoría")
    void aLegacyRowCanOnlyBeResolved() throws Exception {
        Shop shop = shop(10);
        Order order = pay(shop, pendingWebpayOrder(shop, 1, null));
        OutboxMessage legacy = outboxRepository.save(OutboxMessage.builder()
                .order(order)
                .type(OutboxType.POS_ORDER)
                .dedupKey("pos-legacy:" + order.getId())
                .status(OutboxStatus.FAILED)
                .attempts(3)
                .lastError("Pendiente de revisión manual (sincronización anterior al outbox)")
                .nextAttemptAt(Instant.now())
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build());

        assertThatThrownBy(() -> TenantContext.runWithin(shop.store(),
                () -> outboxAdminService.retry(legacy.getId(), "Reintento", true)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("solo puede marcarse resuelto");
        assertThatThrownBy(() -> TenantContext.runWithin(shop.store(),
                () -> outboxAdminService.resolve(legacy.getId(), "  ")))
                .isInstanceOf(BusinessException.class);

        TenantContext.runWithin(shop.store(),
                () -> outboxAdminService.resolve(legacy.getId(), "La venta está en el POS desde marzo"));

        OutboxMessage resolved = reload(legacy);
        assertThat(resolved.getStatus()).isEqualTo(OutboxStatus.SKIPPED);
        assertThat(resolved.getLastError()).isEqualTo("Resuelta a mano: La venta está en el POS desde marzo");
        assertThat(AuditRows.forStore(jdbc, shop.store().getId(), "OUTBOX_RESOLVE")).hasSize(1);
        // Ya no está fallida: una segunda resolución no hace nada y lo dice.
        assertThatThrownBy(() -> TenantContext.runWithin(shop.store(),
                () -> outboxAdminService.resolve(legacy.getId(), "Otra vez")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("ya no está fallido");
        verify(posClient, never()).notifyOrder(any(), any());
    }

    @Test
    @DisplayName("Dos operadores reintentan a la vez la misma venta: una sola vuelve a la cola y sale una vez")
    void twoSimultaneousRetriesRequeueOnce() throws Exception {
        Shop shop = shop(10);
        Order order = pay(shop, pendingWebpayOrder(shop, 1, null));
        OutboxMessage sale = row(order, OutboxType.POS_ORDER);
        jdbc.update("UPDATE outbox_messages SET status = 'FAILED', attempts = 1, last_error = ? WHERE id = ?",
                "Resultado incierto, la venta pudo haber llegado al POS: Read timed out", sale.getId());

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Future<Boolean>> results = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            results.add(pool.submit(() -> {
                start.await();
                try {
                    TenantContext.runWithin(shop.store(),
                            () -> outboxAdminService.retry(sale.getId(), "Revisado en el POS", true));
                    return true;
                } catch (BusinessException e) {
                    return false;
                }
            }));
        }
        start.countDown();
        List<Boolean> outcomes = new ArrayList<>();
        for (Future<Boolean> result : results) {
            outcomes.add(result.get(30, TimeUnit.SECONDS));
        }
        pool.shutdown();

        assertThat(Collections.frequency(outcomes, true)).isEqualTo(1);
        assertThat(AuditRows.forStore(jdbc, shop.store().getId(), "OUTBOX_RETRY")).hasSize(1);

        when(posClient.notifyOrder(any(), any())).thenReturn(PosDelivery.sent(Map.of()));
        dispatcher(shop).dispatch(OutboxChannel.POS);
        dispatcher(shop).dispatch(OutboxChannel.POS);
        verify(posClient, times(1)).notifyOrder(any(), any());
        assertThat(reload(sale).getStatus()).isEqualTo(OutboxStatus.SENT);
    }

    // --- ayudas -----------------------------------------------------------------------------------------------------

    private OutboxMessage row(Order order, OutboxType type) {
        return outbox(order).stream().filter(m -> m.getType() == type).findFirst().orElseThrow();
    }

    private OutboxMessage reload(OutboxMessage row) {
        return outboxRepository.findById(row.getId()).orElseThrow();
    }

    /** Como si el último cambio de la fila fuera de hace {@code age}: para el despachador, una huérfana. */
    private void age(OutboxMessage row, Duration age) {
        jdbc.update("UPDATE outbox_messages SET updated_at = now() - make_interval(secs => ?) WHERE id = ?",
                (double) age.toSeconds(), row.getId());
    }

    /** Adelanta la espera del siguiente intento. */
    private void makeDue(OutboxMessage row) {
        jdbc.update("UPDATE outbox_messages SET next_attempt_at = now() - interval '1 second' WHERE id = ?", row.getId());
    }
}
