package org.uvo.uvostore.service.metrics;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.ToDoubleFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.uvo.uvostore.entity.outbox.enums.OutboxChannel;
import org.uvo.uvostore.entity.outbox.enums.OutboxStatus;
import org.uvo.uvostore.entity.outbox.enums.OutboxType;
import org.uvo.uvostore.repository.OrderRefundRepository;
import org.uvo.uvostore.repository.OrderRepository;
import org.uvo.uvostore.repository.OutboxMessageRepository;

/**
 * PROD-15b. Lo que está pendiente y no debería acumularse, como gauges: cada uno es un conteo de la base, en todas
 * las tiendas y sin la tienda como etiqueta (ver {@link BusinessMetrics}). Son la base de las alertas de PROD-15c.
 *
 * <ul>
 *   <li>{@value #PAID_WITHOUT_STOCK}: cobradas con el stock sin descontar, pasado el margen de
 *       {@code app.stock-recovery.after-minutes} (lo que recoge {@code StockRecoveryJob}, PROD-13a).</li>
 *   <li>{@value #PENDING_STALE}: órdenes {@code PENDING} creadas hace más de {@code app.reconciliation.stale-hours}.</li>
 *   <li>{@value #REFUNDS_PENDING}: reembolsos cuyo resultado no se conoce (F12).</li>
 *   <li>{@value #OUTBOX_PENDING} y {@value #OUTBOX_OLDEST_PENDING}, por canal: la cola del outbox (PROD-13b), con
 *       {@code IN_FLIGHT}, y cuánto lleva esperando lo más viejo. Con {@code channel=pos}, que suba la antigüedad es
 *       la señal de que el POS no está recibiendo las ventas.</li>
 *   <li>{@value #OUTBOX_FAILED}, por canal: entregas fallidas de forma definitiva. Con {@code channel=pos} son las
 *       ventas que el POS no tiene y esperan revisión manual. <b>No baja sola</b>: no hay un flujo que resuelva un
 *       {@code FAILED}, así que una alerta tiene que mirar si sube, no su valor.</li>
 * </ul>
 *
 * <p>La foto se calcula al leer las métricas y se guarda {@code app.metrics.backlog-refresh-seconds}: no ocupa un
 * hilo del planificador (hay uno por trabajo, ver {@code spring.task.scheduling.pool.size}) y no consulta la base si
 * nadie lee. Si la consulta falla se conserva la foto anterior; si nunca hubo una, los gauges dan {@code NaN}, que
 * Prometheus distingue de un cero.
 */
@Component
public class OperationalBacklogMetrics implements MeterBinder {

    private static final Logger log = LoggerFactory.getLogger(OperationalBacklogMetrics.class);

    public static final String PAID_WITHOUT_STOCK = "uvostore.orders.paid.without.stock";
    public static final String PENDING_STALE = "uvostore.orders.pending.stale";
    public static final String REFUNDS_PENDING = "uvostore.refunds.pending";
    public static final String OUTBOX_PENDING = "uvostore.outbox.pending";
    public static final String OUTBOX_OLDEST_PENDING = "uvostore.outbox.oldest.pending";
    public static final String OUTBOX_FAILED = "uvostore.outbox.failed";

    private final OrderRepository orderRepository;
    private final OrderRefundRepository refundRepository;
    private final OutboxMessageRepository outboxRepository;
    private final Duration refreshEvery;
    private final Duration stockMargin;
    private final Duration staleAfter;
    private final Clock clock;

    private Snapshot snapshot;
    private Instant takenAt;

    @Autowired
    public OperationalBacklogMetrics(OrderRepository orderRepository, OrderRefundRepository refundRepository,
                                     OutboxMessageRepository outboxRepository,
                                     @Value("${app.metrics.backlog-refresh-seconds:60}") int refreshSeconds,
                                     @Value("${app.stock-recovery.after-minutes:15}") int stockMarginMinutes,
                                     @Value("${app.reconciliation.stale-hours:24}") int staleHours) {
        this(orderRepository, refundRepository, outboxRepository, Duration.ofSeconds(refreshSeconds),
                Duration.ofMinutes(stockMarginMinutes), Duration.ofHours(staleHours), Clock.systemUTC());
    }

    OperationalBacklogMetrics(OrderRepository orderRepository, OrderRefundRepository refundRepository,
                              OutboxMessageRepository outboxRepository, Duration refreshEvery, Duration stockMargin,
                              Duration staleAfter, Clock clock) {
        this.orderRepository = orderRepository;
        this.refundRepository = refundRepository;
        this.outboxRepository = outboxRepository;
        this.refreshEvery = refreshEvery;
        this.stockMargin = stockMargin;
        this.staleAfter = staleAfter;
        this.clock = clock;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        gauge(registry, PAID_WITHOUT_STOCK, "Órdenes cobradas con el stock sin descontar, pasado el margen",
                Snapshot::paidWithoutStock, null);
        gauge(registry, PENDING_STALE, "Órdenes PENDING más viejas que app.reconciliation.stale-hours",
                Snapshot::pendingStale, null);
        gauge(registry, REFUNDS_PENDING, "Reembolsos con el resultado sin conocer",
                Snapshot::refundsPending, null);
        for (OutboxChannel channel : OutboxChannel.values()) {
            gauge(registry, OUTBOX_PENDING, "Filas del outbox en cola (PENDING o IN_FLIGHT)",
                    s -> s.pending().getOrDefault(channel, 0L), channel);
            gauge(registry, OUTBOX_FAILED, "Filas del outbox fallidas de forma definitiva (revisión manual)",
                    s -> s.failed().getOrDefault(channel, 0L), channel);
            Gauge.builder(OUTBOX_OLDEST_PENDING, this, metrics -> metrics.oldestPendingSeconds(channel))
                    .description("Cuánto lleva en cola la fila más vieja del outbox")
                    .baseUnit("seconds")
                    .tag("channel", BusinessMetrics.tag(channel))
                    .register(registry);
        }
    }

    private void gauge(MeterRegistry registry, String name, String description, ToDoubleFunction<Snapshot> value,
                       OutboxChannel channel) {
        Gauge.Builder<OperationalBacklogMetrics> builder = Gauge.builder(name, this, metrics -> {
            Snapshot current = metrics.current();
            return current == null ? Double.NaN : value.applyAsDouble(current);
        }).description(description);
        if (channel != null) {
            builder.tag("channel", BusinessMetrics.tag(channel));
        }
        builder.register(registry);
    }

    /** Se mide contra el reloj de la lectura, no el de la foto: entre dos fotos, lo que espera sigue envejeciendo. */
    private double oldestPendingSeconds(OutboxChannel channel) {
        Snapshot current = current();
        if (current == null) {
            return Double.NaN;
        }
        Instant oldest = current.oldestPending().get(channel);
        return oldest == null ? 0 : Math.max(0, Duration.between(oldest, clock.instant()).toSeconds());
    }

    /** La foto vigente, o null si nunca se pudo tomar una. Sincronizado: una lectura de Prometheus pide cada gauge. */
    synchronized Snapshot current() {
        Instant now = clock.instant();
        if (snapshot == null || !now.isBefore(takenAt.plus(refreshEvery))) {
            try {
                snapshot = load(now);
                takenAt = now;
            } catch (RuntimeException e) {
                // Sin la base no hay foto nueva. La anterior es mejor que nada, y readiness ya dice que la base cayó.
                log.warn("No se pudieron leer los pendientes para las métricas: {}", e.getMessage());
            }
        }
        return snapshot;
    }

    private Snapshot load(Instant now) {
        Map<OutboxChannel, Long> pending = new EnumMap<>(OutboxChannel.class);
        Map<OutboxChannel, Long> failed = new EnumMap<>(OutboxChannel.class);
        Map<OutboxChannel, Instant> oldestPending = new EnumMap<>(OutboxChannel.class);
        List<Object[]> rows = outboxRepository.countBacklogByTypeAndStatus();
        for (Object[] row : rows) {
            OutboxChannel channel = ((OutboxType) row[0]).channel();
            OutboxStatus status = (OutboxStatus) row[1];
            long count = ((Number) row[2]).longValue();
            if (status == OutboxStatus.FAILED) {
                failed.merge(channel, count, Long::sum);
            } else {
                pending.merge(channel, count, Long::sum);
                Instant oldest = (Instant) row[3];
                if (oldest != null) {
                    oldestPending.merge(channel, oldest, (a, b) -> a.isBefore(b) ? a : b);
                }
            }
        }
        return new Snapshot(
                orderRepository.countPaidWithoutStockApplied(now.minus(stockMargin)),
                orderRepository.countPendingCreatedBefore(now.minus(staleAfter)),
                refundRepository.countPending(),
                pending, failed, oldestPending);
    }

    record Snapshot(long paidWithoutStock, long pendingStale, long refundsPending, Map<OutboxChannel, Long> pending,
                    Map<OutboxChannel, Long> failed, Map<OutboxChannel, Instant> oldestPending) {
    }
}
