package org.uvo.uvostore.outbox;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.MDC;
import org.uvo.uvostore.config.LogContext;
import org.uvo.uvostore.entity.order.Order;
import org.uvo.uvostore.entity.outbox.OutboxMessage;
import org.uvo.uvostore.entity.outbox.enums.OutboxChannel;
import org.uvo.uvostore.entity.outbox.enums.OutboxStatus;
import org.uvo.uvostore.entity.outbox.enums.OutboxType;
import org.uvo.uvostore.entity.tenant.Store;
import org.uvo.uvostore.repository.OutboxMessageRepository;
import org.uvo.uvostore.security.TenantContext;
import org.uvo.uvostore.service.AdvisoryLock;
import org.uvo.uvostore.service.metrics.BusinessMetrics;
import org.uvo.uvostore.service.outbox.OutboxDispatcher;
import org.uvo.uvostore.service.outbox.OutboxHandler;
import org.uvo.uvostore.service.outbox.OutboxResult;
import org.uvo.uvostore.service.outbox.OutboxStore;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * PROD-13b. Qué hace el despachador con cada resultado de entrega. Sin base de datos: lo que se comprueba
 * es la decisión —qué fila se cierra, cuál vuelve a la cola y cuándo, cuál queda para revisión manual—, y el
 * recorrido con transacciones de verdad está en {@code OutboxCrashRecoveryTest}.
 *
 * <p>La regla que más importa: una venta al POS que <b>pudo haber llegado</b> no se reenvía mientras el POS
 * no confirme su idempotencia, porque cada envío emite un documento tributario.
 */
class OutboxDispatcherTest {

    private static final AtomicLong SEQ = new AtomicLong(1);

    private final OutboxMessageRepository repository = mock(OutboxMessageRepository.class);
    private final OutboxStore store = mock(OutboxStore.class);
    private final AdvisoryLock advisoryLock = mock(AdvisoryLock.class);
    private final Map<OutboxType, Function<OutboxMessage, OutboxResult>> behaviour = new HashMap<>();
    private final List<OutboxMessage> handled = new ArrayList<>();
    private final List<Long> tenantsSeen = new ArrayList<>();
    private final List<OutboxMessage> afterDelivered = new ArrayList<>();
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final List<String> requestIdsSeen = new ArrayList<>();
    private final List<String> storeIdsInMdc = new ArrayList<>();

    @BeforeEach
    void setUp() {
        when(advisoryLock.runExclusively(anyLong(), anyString(), any())).thenAnswer(invocation -> {
            ((Runnable) invocation.getArgument(2)).run();
            return true;
        });
        when(store.claim(anyLong(), any())).thenReturn(true);
        when(store.finish(anyLong(), anyInt(), any(), any(), any())).thenReturn(true);
        when(store.reschedule(anyLong(), anyInt(), any(), any(), any())).thenReturn(true);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        MDC.clear();
    }

    @Test
    @DisplayName("PROD-15b: la entrega corre con el id de la petición que encoló la fila, y luego vuelve el de la pasada")
    void theDeliveryRunsUnderTheIdOfTheRequestThatQueuedIt() {
        OutboxMessage row = posRow(1L);
        row.setRequestId("it-pago-15b-0001");
        givenDue(OutboxChannel.POS, row);
        LogContext.putRequestId("job-0123456789ab");

        dispatcher(false).dispatch(OutboxChannel.POS);

        // handle y afterDelivered: los dos con el id de origen (PosClient lo manda al POS desde aquí).
        assertThat(requestIdsSeen).containsExactly("it-pago-15b-0001", "it-pago-15b-0001");
        assertThat(storeIdsInMdc).containsExactly(row.getOrder().getStore().getId().toString());
        assertThat(MDC.get(LogContext.REQUEST_ID)).isEqualTo("job-0123456789ab");
        assertThat(MDC.get(LogContext.STORE_ID)).isNull();
    }

    @Test
    @DisplayName("PROD-15b: una fila sin id (anterior a V30) se entrega con el de la pasada")
    void aRowWithoutAnIdKeepsTheIdOfTheRun() {
        OutboxMessage row = posRow(1L);
        givenDue(OutboxChannel.POS, row);
        LogContext.putRequestId("job-0123456789ab");

        dispatcher(false).dispatch(OutboxChannel.POS);

        assertThat(requestIdsSeen).containsExactly("job-0123456789ab", "job-0123456789ab");
    }

    @Test
    @DisplayName("PROD-15b: cada intento suma a su canal y resultado; una huérfana cuenta como incierta")
    void eachAttemptIsCountedByChannelAndOutcome() {
        OutboxMessage delivered = posRow(1L);
        OutboxMessage rejected = posRow(2L);
        OutboxMessage retried = posRow(3L);
        OutboxMessage email = row(OutboxType.ORDER_PLACED_EMAIL, null);
        OutboxMessage orphan = posRow(4L);
        givenDue(OutboxChannel.POS, delivered, rejected, retried);
        givenDue(OutboxChannel.MAIL, email);
        when(repository.findOrphans(eq(OutboxType.of(OutboxChannel.POS)), any(), any())).thenReturn(List.of(orphan));
        behaviour.put(OutboxType.POS_ORDER, m -> m == delivered ? OutboxResult.delivered(null)
                : m == rejected ? OutboxResult.permanent("HTTP 422") : OutboxResult.retryable("Connection refused"));
        behaviour.put(OutboxType.ORDER_PLACED_EMAIL, m -> OutboxResult.skipped("sin correo"));

        OutboxDispatcher dispatcher = dispatcher(false);
        dispatcher.dispatch(OutboxChannel.POS);
        dispatcher.dispatch(OutboxChannel.MAIL);

        assertThat(deliveries("pos", "delivered")).isEqualTo(1);
        assertThat(deliveries("pos", "permanent")).isEqualTo(1);
        assertThat(deliveries("pos", "retryable")).isEqualTo(1);
        assertThat(deliveries("pos", "uncertain")).isEqualTo(1);
        assertThat(deliveries("pos", "skipped")).isZero();
        assertThat(deliveries("mail", "skipped")).isEqualTo(1);
        assertThat(deliveries("mail", "delivered")).isZero();
    }

    private double deliveries(String channel, String outcome) {
        return meterRegistry.get(BusinessMetrics.OUTBOX_DELIVERIES)
                .tag("channel", channel).tag("outcome", outcome).counter().count();
    }

    @Test
    @DisplayName("Una entrega correcta cierra la fila como SENT y luego corre lo posterior")
    void aDeliveryIsClosedAsSent() {
        OutboxMessage row = posRow(1L);
        givenDue(OutboxChannel.POS, row);
        behaviour.put(OutboxType.POS_ORDER, m -> OutboxResult.delivered(Map.of("ok", true)));

        dispatcher(false).dispatch(OutboxChannel.POS);

        verify(store).finish(eq(row.getId()), eq(1), eq(OutboxStatus.SENT), any(), any());
        assertThat(afterDelivered).containsExactly(row);
    }

    @Test
    @DisplayName("Lo que seguro no salió vuelve a la cola con espera creciente")
    void aRetryableFailureIsRescheduledWithBackoff() {
        OutboxMessage row = posRow(1L);
        row.setAttempts(2);
        givenDue(OutboxChannel.POS, row);
        behaviour.put(OutboxType.POS_ORDER, m -> OutboxResult.retryable("Connection refused"));

        Instant before = Instant.now();
        dispatcher(false).dispatch(OutboxChannel.POS);

        // Tercer intento → 4 minutos.
        ArgumentCaptor<Instant> next = ArgumentCaptor.forClass(Instant.class);
        verify(store).reschedule(eq(row.getId()), eq(3), eq("Connection refused"), next.capture(), any());
        assertThat(next.getValue()).isBetween(before.plus(Duration.ofMinutes(4)), Instant.now().plus(Duration.ofMinutes(4)));
        verify(store, never()).finish(anyLong(), anyInt(), any(), any(), any());
    }

    @Test
    @DisplayName("Una venta al POS de resultado incierto queda FAILED: no se reenvía a ciegas")
    void anUncertainPosSaleFailsWithoutRetry() {
        OutboxMessage row = posRow(1L);
        givenDue(OutboxChannel.POS, row);
        behaviour.put(OutboxType.POS_ORDER, m -> OutboxResult.uncertain("Read timed out"));

        dispatcher(false).dispatch(OutboxChannel.POS);

        ArgumentCaptor<String> error = ArgumentCaptor.forClass(String.class);
        verify(store).finish(eq(row.getId()), eq(1), eq(OutboxStatus.FAILED), error.capture(), any());
        assertThat(error.getValue()).contains("pudo haber llegado al POS").contains("Read timed out");
        verify(store, never()).reschedule(anyLong(), anyInt(), any(), any(), any());
    }

    @Test
    @DisplayName("Con el reintento del POS encendido, lo incierto sí vuelve a la cola")
    void anUncertainPosSaleIsRetriedWhenIdempotencyIsConfirmed() {
        OutboxMessage row = posRow(1L);
        givenDue(OutboxChannel.POS, row);
        behaviour.put(OutboxType.POS_ORDER, m -> OutboxResult.uncertain("Read timed out"));

        dispatcher(true).dispatch(OutboxChannel.POS);

        verify(store).reschedule(eq(row.getId()), eq(1), any(), any(), any());
        verify(store, never()).finish(anyLong(), anyInt(), any(), any(), any());
    }

    @Test
    @DisplayName("Un correo incierto se reintenta: un duplicado es una molestia, no un documento")
    void anUncertainEmailIsRetried() {
        OutboxMessage row = row(OutboxType.ORDER_PLACED_EMAIL, null);
        givenDue(OutboxChannel.MAIL, row);
        behaviour.put(OutboxType.ORDER_PLACED_EMAIL, m -> OutboxResult.uncertain("SMTP colgado"));

        dispatcher(false).dispatch(OutboxChannel.MAIL);

        verify(store).reschedule(eq(row.getId()), eq(1), any(), any(), any());
    }

    @Test
    @DisplayName("Un rechazo por contenido queda FAILED en el primer intento")
    void aPermanentFailureFailsAtOnce() {
        OutboxMessage row = posRow(1L);
        givenDue(OutboxChannel.POS, row);
        behaviour.put(OutboxType.POS_ORDER, m -> OutboxResult.permanent("HTTP 422"));

        dispatcher(true).dispatch(OutboxChannel.POS);

        verify(store).finish(eq(row.getId()), eq(1), eq(OutboxStatus.FAILED), any(), any());
    }

    @Test
    @DisplayName("Pasado el plazo, lo reintentable se da por fallido")
    void aRetryableFailureGivesUpAfterTheDeadline() {
        OutboxMessage row = posRow(1L);
        row.setCreatedAt(Instant.now().minus(Duration.ofHours(49)));
        givenDue(OutboxChannel.POS, row);
        behaviour.put(OutboxType.POS_ORDER, m -> OutboxResult.retryable("Connection refused"));

        dispatcher(false).dispatch(OutboxChannel.POS);

        ArgumentCaptor<String> error = ArgumentCaptor.forClass(String.class);
        verify(store).finish(eq(row.getId()), eq(1), eq(OutboxStatus.FAILED), error.capture(), any());
        assertThat(error.getValue()).contains("48 h");
    }

    @Test
    @DisplayName("Lo omitido se cierra como SKIPPED con su motivo")
    void aSkipIsClosedWithItsReason() {
        OutboxMessage row = posRow(1L);
        givenDue(OutboxChannel.POS, row);
        behaviour.put(OutboxType.POS_ORDER, m -> OutboxResult.skipped("Integración POS deshabilitada"));

        dispatcher(false).dispatch(OutboxChannel.POS);

        verify(store).finish(eq(row.getId()), eq(1), eq(OutboxStatus.SKIPPED), eq("Integración POS deshabilitada"), any());
        assertThat(afterDelivered).isEmpty();
    }

    @Test
    @DisplayName("Una fila huérfana en vuelo es incierta: sin reintento del POS, queda FAILED y no se vuelve a enviar")
    void anOrphanInFlightIsUncertain() {
        OutboxMessage orphan = posRow(1L);
        orphan.setStatus(OutboxStatus.IN_FLIGHT);
        orphan.setAttempts(1);
        when(repository.findOrphans(eq(OutboxType.of(OutboxChannel.POS)), any(), any())).thenReturn(List.of(orphan));

        dispatcher(false).dispatch(OutboxChannel.POS);

        // El intento ya estaba contado al reclamarla: se cierra con ese mismo número.
        ArgumentCaptor<String> error = ArgumentCaptor.forClass(String.class);
        verify(store).finish(eq(orphan.getId()), eq(1), eq(OutboxStatus.FAILED), error.capture(), any());
        assertThat(error.getValue()).contains("Huérfana en vuelo");
        assertThat(handled).as("una huérfana no se vuelve a entregar").isEmpty();
        verify(store, never()).claim(anyLong(), any());
    }

    @Test
    @DisplayName("Un POS que falla no quema el resto de sus filas ni retiene a otra empresa")
    void anUnreachableCompanyIsSkippedForTheRestOfTheRun() {
        OutboxMessage firstOfA = posRow(1L);
        OutboxMessage secondOfA = posRow(1L);
        OutboxMessage ofB = posRow(2L);
        givenDue(OutboxChannel.POS, firstOfA, secondOfA, ofB);
        behaviour.put(OutboxType.POS_ORDER, m -> m.getCompanyId() == 1L
                ? OutboxResult.retryable("Connection refused")
                : OutboxResult.delivered(null));

        dispatcher(false).dispatch(OutboxChannel.POS);

        assertThat(handled).containsExactly(firstOfA, ofB);
        verify(store, never()).claim(eq(secondOfA.getId()), any());
        verify(store).finish(eq(ofB.getId()), eq(1), eq(OutboxStatus.SENT), any(), any());
    }

    @Test
    @DisplayName("Un handler que lanza cuenta como incierto, y el lote sigue")
    void aThrowingHandlerIsUncertainAndTheBatchGoesOn() {
        OutboxMessage broken = row(OutboxType.ORDER_PLACED_EMAIL, null);
        OutboxMessage healthy = row(OutboxType.ORDER_PLACED_EMAIL, null);
        givenDue(OutboxChannel.MAIL, broken, healthy);
        behaviour.put(OutboxType.ORDER_PLACED_EMAIL, m -> {
            if (m == broken) {
                throw new IllegalStateException("bug");
            }
            return OutboxResult.delivered(null);
        });

        dispatcher(false).dispatch(OutboxChannel.MAIL);

        verify(store).reschedule(eq(broken.getId()), eq(1), any(), any(), any());
        verify(store).finish(eq(healthy.getId()), eq(1), eq(OutboxStatus.SENT), any(), any());
    }

    @Test
    @DisplayName("Cada fila se entrega con el tenant de su tienda, y el contexto queda limpio")
    void eachRowRunsUnderItsOwnTenant() {
        OutboxMessage first = row(OutboxType.ORDER_PLACED_EMAIL, null);
        OutboxMessage second = row(OutboxType.ORDER_PLACED_EMAIL, null);
        givenDue(OutboxChannel.MAIL, first, second);
        behaviour.put(OutboxType.ORDER_PLACED_EMAIL, m -> OutboxResult.delivered(null));

        dispatcher(false).dispatch(OutboxChannel.MAIL);

        assertThat(tenantsSeen).containsExactly(first.getOrder().getStore().getId(), second.getOrder().getStore().getId());
        assertThat(TenantContext.get()).isNull();
    }

    @Test
    @DisplayName("Si otra instancia tiene el cerrojo, la corrida no hace nada")
    void aRunWithoutTheLockDoesNothing() {
        // doReturn y no when(): when() llamaría al mock con argumentos nulos y dispararía la respuesta de
        // setUp, que ejecuta el Runnable.
        doReturn(false).when(advisoryLock).runExclusively(anyLong(), anyString(), any());

        dispatcher(false).dispatch(OutboxChannel.POS);

        verifyNoInteractions(repository);
        verifyNoInteractions(store);
    }

    @Test
    @DisplayName("Si otro proceso reclamó la fila entretanto, no se entrega")
    void aRowClaimedElsewhereIsNotDelivered() {
        OutboxMessage row = posRow(1L);
        givenDue(OutboxChannel.POS, row);
        behaviour.put(OutboxType.POS_ORDER, m -> OutboxResult.delivered(null));
        when(store.claim(eq(row.getId()), any())).thenReturn(false);

        dispatcher(false).dispatch(OutboxChannel.POS);

        assertThat(handled).isEmpty();
    }

    @Test
    @DisplayName("Al cerrar el contexto no se reclama nada nuevo")
    void nothingIsClaimedOnceTheContextCloses() {
        OutboxDispatcher dispatcher = dispatcher(false);
        dispatcher.onContextClosed();

        dispatcher.dispatch(OutboxChannel.POS);

        verifyNoInteractions(advisoryLock);
    }

    // --- fixtures ----------------------------------------------------------------------------------

    private OutboxDispatcher dispatcher(boolean posRetryEnabled) {
        List<OutboxHandler> handlers = new ArrayList<>();
        for (OutboxType type : OutboxType.values()) {
            handlers.add(new RecordingHandler(type));
        }
        return new OutboxDispatcher(repository, store, advisoryLock, handlers, posRetryEnabled, 50, 48, 120,
                new BusinessMetrics(meterRegistry));
    }

    private void givenDue(OutboxChannel channel, OutboxMessage... rows) {
        when(repository.findDue(eq(OutboxType.of(channel)), any(), any())).thenReturn(List.of(rows));
    }

    private static OutboxMessage posRow(Long companyId) {
        return row(OutboxType.POS_ORDER, companyId);
    }

    private static OutboxMessage row(OutboxType type, Long companyId) {
        long seq = SEQ.getAndIncrement();
        Store store = Store.builder().name("Tienda " + seq).slug("tienda-" + seq).build();
        store.setId(seq);
        Order order = new Order();
        order.setId(seq);
        order.setOrderNumber("ORD-" + seq);
        order.setStore(store);
        return OutboxMessage.builder()
                .id(seq)
                .order(order)
                .type(type)
                .companyId(companyId)
                .dedupKey(type + ":" + seq)
                .status(OutboxStatus.PENDING)
                .attempts(0)
                .createdAt(Instant.now())
                .nextAttemptAt(Instant.now())
                .build();
    }

    private final class RecordingHandler implements OutboxHandler {

        private final OutboxType type;

        RecordingHandler(OutboxType type) {
            this.type = type;
        }

        @Override
        public OutboxType type() {
            return type;
        }

        @Override
        public OutboxResult handle(OutboxMessage message) {
            handled.add(message);
            tenantsSeen.add(TenantContext.currentStoreId());
            requestIdsSeen.add(MDC.get(LogContext.REQUEST_ID));
            storeIdsInMdc.add(MDC.get(LogContext.STORE_ID));
            return behaviour.getOrDefault(type, m -> OutboxResult.delivered(null)).apply(message);
        }

        @Override
        public void afterDelivered(OutboxMessage message, OutboxResult result) {
            afterDelivered.add(message);
            requestIdsSeen.add(MDC.get(LogContext.REQUEST_ID));
        }
    }
}
