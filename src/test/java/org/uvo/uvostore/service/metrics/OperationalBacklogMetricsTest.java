package org.uvo.uvostore.service.metrics;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.uvo.uvostore.entity.order.Order;
import org.uvo.uvostore.entity.order.OrderRefund;
import org.uvo.uvostore.entity.order.enums.FulfillmentStatus;
import org.uvo.uvostore.entity.order.enums.OrderStatus;
import org.uvo.uvostore.entity.order.enums.PaymentStatus;
import org.uvo.uvostore.entity.order.enums.RefundStatus;
import org.uvo.uvostore.entity.order.enums.RefundType;
import org.uvo.uvostore.entity.outbox.OutboxMessage;
import org.uvo.uvostore.entity.outbox.enums.OutboxChannel;
import org.uvo.uvostore.entity.outbox.enums.OutboxStatus;
import org.uvo.uvostore.entity.outbox.enums.OutboxType;
import org.uvo.uvostore.entity.tenant.Store;
import org.uvo.uvostore.repository.OrderRefundRepository;
import org.uvo.uvostore.repository.OrderRepository;
import org.uvo.uvostore.repository.OutboxMessageRepository;
import org.uvo.uvostore.support.IntegrationTestSupport;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PROD-15b. Lo que cuenta cada gauge de pendientes, contra la base de verdad.
 *
 * <p>Los conteos son de todo el sistema, y la base de los tests es compartida: cada caso mide la diferencia entre
 * antes y después de sus propias filas, que viven en la transacción del test y se deshacen al terminar.
 */
class OperationalBacklogMetricsTest extends IntegrationTestSupport {

    private static final Duration STOCK_MARGIN = Duration.ofMinutes(15);
    private static final Duration STALE_AFTER = Duration.ofHours(24);

    @Autowired
    private OrderRepository orderRepository;
    @Autowired
    private OrderRefundRepository refundRepository;
    @Autowired
    private OutboxMessageRepository outboxRepository;

    @Test
    @DisplayName("Órdenes: cobradas sin stock pasado el margen, y PENDING más viejas que el plazo")
    void theOrderGaugesFollowTheirCriteria() {
        OperationalBacklogMetrics metrics = live();
        OperationalBacklogMetrics.Snapshot before = metrics.current();
        Store store = createStore("backlog-orders");

        paid(store, false, Instant.now().minus(Duration.ofMinutes(20)));  // cuenta
        paid(store, false, Instant.now().minus(Duration.ofMinutes(5)));   // dentro del margen: el listener va lento
        paid(store, true, Instant.now().minus(Duration.ofHours(3)));      // ya descontada
        pending(store, OrderStatus.PENDING, Duration.ofHours(25));        // cuenta
        pending(store, OrderStatus.PENDING, Duration.ofHours(1));         // todavía no es vieja
        pending(store, OrderStatus.CANCELLED, Duration.ofHours(30));      // ya no está pendiente

        OperationalBacklogMetrics.Snapshot after = metrics.current();
        assertThat(after.paidWithoutStock() - before.paidWithoutStock()).isEqualTo(1);
        assertThat(after.pendingStale() - before.pendingStale()).isEqualTo(1);
    }

    @Test
    @DisplayName("Reembolsos: solo los PENDING, los de resultado desconocido")
    void theRefundGaugeCountsOnlyPending() {
        OperationalBacklogMetrics metrics = live();
        long before = metrics.current().refundsPending();
        Order order = paid(createStore("backlog-refunds"), true, Instant.now());

        refund(order, RefundStatus.PENDING);
        refund(order, RefundStatus.COMPLETED);
        refund(order, RefundStatus.FAILED);

        assertThat(metrics.current().refundsPending() - before).isEqualTo(1);
    }

    @Test
    @DisplayName("Outbox: la cola incluye lo que está en vuelo, por canal; lo fallido aparte; lo entregado no cuenta")
    void theOutboxGaugesSplitByChannelAndState() {
        OperationalBacklogMetrics metrics = live();
        OperationalBacklogMetrics.Snapshot before = metrics.current();
        Order order = paid(createStore("backlog-outbox"), true, Instant.now());

        outbox(order, OutboxType.POS_ORDER, OutboxStatus.PENDING, Duration.ofHours(2));
        outbox(order, OutboxType.POS_ORDER, OutboxStatus.IN_FLIGHT, Duration.ofMinutes(1));
        outbox(order, OutboxType.POS_ORDER, OutboxStatus.FAILED, Duration.ofDays(3));
        outbox(order, OutboxType.POS_ORDER, OutboxStatus.SENT, Duration.ofDays(5));
        outbox(order, OutboxType.ORDER_CONFIRMATION_EMAIL, OutboxStatus.PENDING, Duration.ofMinutes(1));
        outbox(order, OutboxType.ORDER_PLACED_EMAIL, OutboxStatus.SKIPPED, Duration.ofMinutes(1));

        OperationalBacklogMetrics.Snapshot after = metrics.current();
        assertThat(delta(after, before, OutboxChannel.POS, true)).isEqualTo(2);
        assertThat(delta(after, before, OutboxChannel.MAIL, true)).isEqualTo(1);
        assertThat(delta(after, before, OutboxChannel.POS, false)).isEqualTo(1);
        assertThat(delta(after, before, OutboxChannel.MAIL, false)).isZero();
        // La fila POS pendiente lleva dos horas: lo más viejo de la cola tiene por lo menos eso (puede haber otra
        // más vieja en la base compartida, nunca una más nueva).
        assertThat(after.oldestPending().get(OutboxChannel.POS))
                .isBeforeOrEqualTo(Instant.now().minus(Duration.ofHours(2)).plusSeconds(5));
    }

    @Test
    @DisplayName("Ninguna métrica propia lleva la tienda como etiqueta: la cardinalidad no crece con las tiendas")
    void noMeterIsTaggedByStore() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        live().bindTo(registry);
        new BusinessMetrics(registry);

        List<Meter> own = registry.getMeters().stream()
                .filter(meter -> meter.getId().getName().startsWith("uvostore."))
                .toList();
        assertThat(own).hasSizeGreaterThan(10);
        assertThat(own).allSatisfy(meter -> assertThat(meter.getId().getTags())
                .noneMatch(tag -> tag.getKey().toLowerCase().contains("store")));
    }

    // --- la foto, sin base ---------------------------------------------------------------------------

    @Test
    @DisplayName("La foto se guarda: dentro del plazo no se vuelve a consultar, y pasado el plazo sí")
    void theSnapshotIsKeptForItsTtl() {
        MutableClock clock = new MutableClock(Instant.parse("2026-10-25T12:00:00Z"));
        Mocks mocks = new Mocks();
        OperationalBacklogMetrics metrics = mocks.metrics(clock);

        metrics.current();
        clock.advance(Duration.ofSeconds(59));
        metrics.current();
        verify(mocks.orders, times(1)).countPaidWithoutStockApplied(any());

        clock.advance(Duration.ofSeconds(1));
        metrics.current();
        verify(mocks.orders, times(2)).countPaidWithoutStockApplied(any());
        // El margen se mide desde el reloj, no se olvida.
        verify(mocks.orders).countPaidWithoutStockApplied(Instant.parse("2026-10-25T11:46:00Z"));
    }

    @Test
    @DisplayName("Si la base falla, se conserva la foto anterior; si nunca hubo una, los gauges dan NaN y no cero")
    void aFailedReadKeepsThePreviousSnapshot() {
        MutableClock clock = new MutableClock(Instant.parse("2026-10-25T12:00:00Z"));
        Mocks mocks = new Mocks();
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        OperationalBacklogMetrics metrics = mocks.metrics(clock);
        metrics.bindTo(registry);

        when(mocks.refunds.countPending()).thenThrow(new IllegalStateException("base caída"));
        assertThat(registry.get(OperationalBacklogMetrics.REFUNDS_PENDING).gauge().value()).isNaN();

        clock.advance(Duration.ofMinutes(2));
        org.mockito.Mockito.reset(mocks.refunds);
        when(mocks.refunds.countPending()).thenReturn(4L);
        assertThat(registry.get(OperationalBacklogMetrics.REFUNDS_PENDING).gauge().value()).isEqualTo(4);

        clock.advance(Duration.ofMinutes(2));
        when(mocks.refunds.countPending()).thenThrow(new IllegalStateException("base caída otra vez"));
        assertThat(registry.get(OperationalBacklogMetrics.REFUNDS_PENDING).gauge().value()).isEqualTo(4);
    }

    @Test
    @DisplayName("La antigüedad de la cola sigue creciendo entre dos fotos, y es cero con la cola vacía")
    void theOldestPendingAgeKeepsGrowing() {
        MutableClock clock = new MutableClock(Instant.parse("2026-10-25T12:00:00Z"));
        Mocks mocks = new Mocks();
        List<Object[]> rows = new ArrayList<>();
        rows.add(new Object[] {OutboxType.POS_ORDER, OutboxStatus.PENDING, 3L, Instant.parse("2026-10-25T11:50:00Z")});
        when(mocks.outbox.countBacklogByTypeAndStatus()).thenReturn(rows);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        mocks.metrics(clock).bindTo(registry);

        assertThat(oldest(registry, "pos")).isEqualTo(600);
        clock.advance(Duration.ofSeconds(30));
        assertThat(oldest(registry, "pos")).as("misma foto, más tarde").isEqualTo(630);
        assertThat(oldest(registry, "mail")).isZero();
        assertThat(registry.get(OperationalBacklogMetrics.OUTBOX_PENDING).tag("channel", "pos").gauge().value())
                .isEqualTo(3);
    }

    // --- fixtures ----------------------------------------------------------------------------------

    private OperationalBacklogMetrics live() {
        // Plazo cero: cada lectura vuelve a contar, para ver el antes y el después dentro del mismo test.
        return new OperationalBacklogMetrics(orderRepository, refundRepository, outboxRepository, Duration.ZERO,
                STOCK_MARGIN, STALE_AFTER, Clock.systemUTC());
    }

    private static long delta(OperationalBacklogMetrics.Snapshot after, OperationalBacklogMetrics.Snapshot before,
                              OutboxChannel channel, boolean pending) {
        return pending
                ? after.pending().getOrDefault(channel, 0L) - before.pending().getOrDefault(channel, 0L)
                : after.failed().getOrDefault(channel, 0L) - before.failed().getOrDefault(channel, 0L);
    }

    private static double oldest(SimpleMeterRegistry registry, String channel) {
        return registry.get(OperationalBacklogMetrics.OUTBOX_OLDEST_PENDING).tag("channel", channel).gauge().value();
    }

    private Order paid(Store store, boolean stockApplied, Instant paidAt) {
        Order order = newOrder(store);
        order.setStatus(OrderStatus.PROCESSING);
        order.setPaymentStatus(PaymentStatus.PAID);
        order.setPaidAt(paidAt);
        order.setStockApplied(stockApplied);
        return orderRepository.saveAndFlush(order);
    }

    /** created_at lo pone Hibernate al insertar (@CreationTimestamp), así que la antigüedad se fija con SQL. */
    private void pending(Store store, OrderStatus status, Duration age) {
        Order order = newOrder(store);
        order.setStatus(status);
        order = orderRepository.saveAndFlush(order);
        jdbcTemplate.update("update orders set created_at = ? where id = ?",
                Timestamp.from(Instant.now().minus(age)), order.getId());
    }

    private void refund(Order order, RefundStatus status) {
        refundRepository.saveAndFlush(OrderRefund.builder()
                .order(order)
                .amount(BigDecimal.ONE)
                .type(RefundType.PARTIAL)
                .status(status)
                .idempotencyKey("backlog-" + nextSeq())
                .build());
    }

    private void outbox(Order order, OutboxType type, OutboxStatus status, Duration age) {
        Instant createdAt = Instant.now().minus(age);
        outboxRepository.saveAndFlush(OutboxMessage.builder()
                .order(order)
                .type(type)
                .companyId(type.channel() == OutboxChannel.POS ? 1L : null)
                .dedupKey("backlog:" + nextSeq())
                .status(status)
                .attempts(0)
                .nextAttemptAt(createdAt)
                .createdAt(createdAt)
                .updatedAt(createdAt)
                .build());
    }

    private static Order newOrder(Store store) {
        Order order = new Order();
        order.setStore(store);
        order.setOrderNumber("ORD-BACKLOG-" + nextSeq());
        order.setCustomerEmail("comprador@test.local");
        order.setCustomerFirstName("Test");
        order.setCustomerLastName("Comprador");
        order.setSubtotal(BigDecimal.valueOf(1000));
        order.setDiscountAmount(BigDecimal.ZERO);
        order.setShippingCost(BigDecimal.ZERO);
        order.setTaxAmount(BigDecimal.ZERO);
        order.setTotal(BigDecimal.valueOf(1000));
        order.setStatus(OrderStatus.PENDING);
        order.setPaymentStatus(PaymentStatus.PENDING);
        order.setFulfillmentStatus(FulfillmentStatus.UNFULFILLED);
        order.setItems(new ArrayList<>());
        return order;
    }

    private static final class Mocks {
        final OrderRepository orders = mock(OrderRepository.class);
        final OrderRefundRepository refunds = mock(OrderRefundRepository.class);
        final OutboxMessageRepository outbox = mock(OutboxMessageRepository.class);

        OperationalBacklogMetrics metrics(Clock clock) {
            return new OperationalBacklogMetrics(orders, refunds, outbox, Duration.ofSeconds(60), Duration.ofMinutes(14),
                    STALE_AFTER, clock);
        }
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
