package org.uvo.uvostore.platform;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.uvo.uvostore.entity.outbox.OutboxMessage;
import org.uvo.uvostore.entity.outbox.enums.OutboxStatus;
import org.uvo.uvostore.entity.outbox.enums.OutboxType;
import org.uvo.uvostore.entity.tenant.Store;
import org.uvo.uvostore.repository.OutboxMessageRepository;
import org.uvo.uvostore.repository.StoreRepository;
import org.uvo.uvostore.repository.UserRepository;
import org.uvo.uvostore.service.platform.StoreOnboardingCommand;
import org.uvo.uvostore.service.platform.StoreOnboardingResponse;
import org.uvo.uvostore.service.platform.StoreOnboardingService;
import org.uvo.uvostore.service.outbox.AccessTokenHash;
import org.uvo.uvostore.support.WithPosIntegration;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * POS-6. Lo que del alta solo se ve con transacciones confirmadas de verdad: el correo del dueño y dos reintentos
 * simultáneos del POS. Aquí cada alta confirma, y {@link #cleanUp} borra lo que dejó.
 *
 * <p>IMP-06. El correo del dueño ya no sale de un oyente {@code AFTER_COMMIT}: es una fila del outbox escrita en la
 * transacción del alta, que existe si y solo si el alta se confirmó. Su entrega (y lo que pasa si el SMTP falla) la
 * prueba {@code AccessEmailOutboxTest}.
 */
@WithPosIntegration
@AutoConfigureMockMvc
class StoreOnboardingCommitTest {

    @Autowired
    private StoreOnboardingService onboarding;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private StoreRepository storeRepository;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private OutboxMessageRepository outboxRepository;
    @Autowired
    private UserRepository userRepository;

    @Value("${app.platform-api-key}")
    private String platformKey;

    private final List<String> slugs = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        for (String slug : slugs) {
            storeRepository.findBySlug(slug).map(Store::getId).ifPresent(storeId -> {
                // PROD-18. La fila de auditoría del alta también se confirmó.
                jdbc.update("DELETE FROM audit_events WHERE store_id = ?", storeId);
                jdbc.update("UPDATE stores SET owner_user_id = NULL WHERE id = ?", storeId);
                jdbc.update("DELETE FROM pos_connections WHERE store_id = ?", storeId);
                jdbc.update("DELETE FROM settings WHERE store_id = ?", storeId);
                jdbc.update("DELETE FROM store_settings WHERE store_id = ?", storeId);
                jdbc.update("DELETE FROM user_roles WHERE user_id IN (SELECT id FROM users WHERE store_id = ?)", storeId);
                jdbc.update("DELETE FROM role_permissions WHERE role_id IN (SELECT id FROM roles WHERE store_id = ?)",
                        storeId);
                jdbc.update("DELETE FROM users WHERE store_id = ?", storeId);
                jdbc.update("DELETE FROM roles WHERE store_id = ?", storeId);
                jdbc.update("DELETE FROM stores WHERE id = ?", storeId);
            });
        }
    }

    @Test
    @DisplayName("La invitación queda en el outbox con el commit, una vez, y un reintento no encola otra")
    void theInvitationIsQueuedOnceWithTheCommitAndNotOnARetry() {
        StoreOnboardingCommand alta = alta();

        StoreOnboardingResponse first = onboarding.createStore(alta);

        assertThat(first.created()).isTrue();
        String token = userRepository.findById(first.adminUserId()).orElseThrow().getInvitationToken();
        assertThat(outboxRepository.findByUserIdOrderByIdDesc(first.adminUserId())).singleElement().satisfies(message -> {
            assertThat(message.getType()).isEqualTo(OutboxType.ADMIN_INVITATION_EMAIL);
            assertThat(message.getStatus()).isEqualTo(OutboxStatus.PENDING);
            assertThat(message.getTokenHash()).isEqualTo(AccessTokenHash.of(token));
            assertThat(message.getStore().getId()).isEqualTo(first.storeId());
        });

        StoreOnboardingResponse retry = onboarding.createStore(alta);

        assertThat(retry.created()).isFalse();
        assertThat(retry.storeId()).isEqualTo(first.storeId());
        assertThat(outboxRepository.findByUserIdOrderByIdDesc(first.adminUserId())).hasSize(1);
    }

    @Test
    @DisplayName("Si el alta se deshace, la invitación no sale")
    void nothingIsSentWhenTheOnboardingRollsBack() {
        // Un correo enviado antes del commit llevaría un token que, deshecha el alta, no existe.
        StoreOnboardingCommand alta = alta();
        Integer invitationsBefore = jdbc.queryForObject(
                "SELECT count(*) FROM outbox_messages WHERE type = 'ADMIN_INVITATION_EMAIL'", Integer.class);

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            onboarding.createStore(alta);
            status.setRollbackOnly();
        });

        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_messages WHERE type = 'ADMIN_INVITATION_EMAIL'",
                Integer.class)).isEqualTo(invitationsBefore);
        assertThat(storeRepository.existsBySlug(alta.slug())).isFalse();
        // PROD-18. Ni la fila de auditoría del alta: va en la misma transacción.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_events WHERE action = 'STORE_CREATE' "
                + "AND details->>'slug' = ?", Integer.class, alta.slug())).isZero();
    }

    @Test
    @DisplayName("El alta por HTTP responde sin esperar al SMTP: el correo queda pendiente en el outbox")
    void theHttpOnboardingAnswersWithoutWaitingForSmtp() throws Exception {
        // Antes un oyente AFTER_COMMIT enviaba con la respuesta ya decidida, y un SMTP caído solo dejaba una línea de
        // log. Ahora el alta no toca el SMTP: lo hace el despachador, que reintenta.
        StoreOnboardingCommand alta = alta();
        StoreOnboardingCommand.PosLink pos = alta.pos();
        String response = mockMvc.perform(post("/api/platform/stores")
                        .header("X-Platform-Key", platformKey)
                        .contentType("application/json")
                        .content("""
                                {"slug":"%s","domain":"","storeName":"%s","adminName":"%s","adminEmail":"%s",
                                 "pos":{"companyId":%d,"companyName":"%s","apiUrl":"%s","outboundKeyId":"%s",
                                 "outboundSecret":"%s","inboundKeyId":"%s","inboundSecret":"%s"}}"""
                                .formatted(alta.slug(), alta.storeName(), alta.adminName(), alta.adminEmail(),
                                        pos.companyId(), pos.companyName(), pos.apiUrl(), pos.outboundKeyId(),
                                        pos.outboundSecret(), pos.inboundKeyId(), pos.inboundSecret())))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.created").value(true))
                    .andReturn().getResponse().getContentAsString();

        assertThat(storeRepository.existsBySlug(alta.slug())).isTrue();
        long ownerId = new ObjectMapper().readTree(response).get("adminUserId").asLong();
        assertThat(outboxRepository.findByUserIdOrderByIdDesc(ownerId)).singleElement()
                .extracting(OutboxMessage::getStatus, OutboxMessage::getAttempts)
                .containsExactly(OutboxStatus.PENDING, 0);
    }

    @Test
    @DisplayName("Dos reintentos a la vez: el segundo espera al primero y devuelve la misma tienda")
    void twoSimultaneousRetriesCreateOneStore() throws Exception {
        // Sin el cerrojo por empresa, los dos verían "no existe" y el segundo acabaría en la restricción única del
        // nick: un 500 para el POS en vez de la tienda que ya se creó.
        StoreOnboardingCommand alta = alta();
        CountDownLatch createdInA = new CountDownLatch(1);
        CountDownLatch releaseA = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            // A crea la tienda y se queda con la transacción abierta.
            Future<StoreOnboardingResponse> first = executor.submit(() ->
                    new TransactionTemplate(transactionManager).execute(status -> {
                        StoreOnboardingResponse response = onboarding.createStore(alta);
                        createdInA.countDown();
                        awaitQuietly(releaseA);
                        return response;
                    }));
            assertThat(createdInA.await(30, TimeUnit.SECONDS)).isTrue();

            // B llega mientras A sigue abierta: tiene que quedarse esperando el cerrojo de la empresa.
            Future<StoreOnboardingResponse> second = executor.submit(() -> onboarding.createStore(alta));
            boolean secondWaitedForTheLock = eventually(Duration.ofSeconds(10), () -> jdbc.queryForObject(
                    "SELECT count(*) FROM pg_locks WHERE locktype = 'advisory' AND NOT granted AND classid = 7312",
                    Integer.class) > 0);

            releaseA.countDown();
            StoreOnboardingResponse a = first.get(30, TimeUnit.SECONDS);
            StoreOnboardingResponse b = second.get(30, TimeUnit.SECONDS);

            assertThat(secondWaitedForTheLock).isTrue();
            assertThat(a.created()).isTrue();
            assertThat(b.created()).isFalse();
            assertThat(b.storeId()).isEqualTo(a.storeId());
            // PROD-18. Una sola fila de alta, confirmada: el reintento que esperó no escribe nada.
            assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_events WHERE action = 'STORE_CREATE' "
                    + "AND store_id = ?", Integer.class, a.storeId())).isEqualTo(1);
        } finally {
            releaseA.countDown();
            executor.shutdownNow();
        }
    }

    private StoreOnboardingCommand alta() {
        long seq = System.nanoTime();
        String slug = "pos6-commit-" + seq;
        slugs.add(slug);
        long companyId = 770_000_000_000L + seq % 1_000_000_000L;
        return new StoreOnboardingCommand(slug, null, "Tienda confirmada", "Dueño", "dueno-" + seq + "@test.local",
                new StoreOnboardingCommand.PosLink(companyId, "Empresa del POS", "http://pos.internal/api",
                        "out-" + companyId, org.uvo.uvostore.support.PosSigning.secret("out-" + companyId),
                        "in-" + companyId, org.uvo.uvostore.support.PosSigning.secret("in-" + companyId)));
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static boolean eventually(Duration timeout, BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(50);
        }
        return false;
    }
}
