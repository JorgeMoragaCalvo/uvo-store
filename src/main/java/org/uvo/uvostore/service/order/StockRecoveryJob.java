package org.uvo.uvostore.service.order;

import io.sentry.Sentry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.uvo.uvostore.entity.order.Order;
import org.uvo.uvostore.repository.OrderRepository;
import org.uvo.uvostore.security.TenantContext;
import org.uvo.uvostore.service.AdvisoryLock;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * PROD-13a. Descuenta el stock de las órdenes cobradas que se quedaron sin descontar.
 *
 * <p>El descuento corre en {@code StockDecrementListener}, después del commit de {@code markPaid} y en el
 * hilo de la petición (a propósito, ver su comentario). Si el proceso muere justo entre las dos cosas,
 * la orden queda {@code PAID} con {@code stock_applied = false} y nada la volvía a mirar: la conciliación
 * de pagos solo busca {@code PENDING}. El inventario seguía vendiendo unidades que ya estaban cobradas.
 *
 * <p>Lo que hace seguro correr esto a la vez que el listener es el reclamo condicional de
 * {@code OrderInventoryService.applyOrderStock}: de los dos, solo uno descuenta. El margen
 * ({@code after-minutes} desde {@code paid_at}) no está para eso, sino para no avisar de una anomalía
 * cuando el listener simplemente todavía no había terminado.
 *
 * <p><b>Activado por defecto</b>, como la conciliación y las órdenes abandonadas: no hay documentos
 * tributarios que duplicar, y el reclamo lo hace idempotente.
 */
@Component
@ConditionalOnProperty(name = "app.stock-recovery.enabled", havingValue = "true", matchIfMissing = true)
public class StockRecoveryJob {

    private static final Logger log = LoggerFactory.getLogger(StockRecoveryJob.class);

    /** La siguiente a la de {@code PaymentReconciliationService}: solo tiene que no repetirse. */
    private static final long ADVISORY_LOCK_KEY = 7_312_025_002L;

    private final OrderRepository orderRepository;
    private final OrderInventoryService orderInventoryService;
    private final AdvisoryLock advisoryLock;
    private final int afterMinutes;
    private final int batchSize;

    public StockRecoveryJob(OrderRepository orderRepository, OrderInventoryService orderInventoryService,
                            AdvisoryLock advisoryLock,
                            @Value("${app.stock-recovery.after-minutes:15}") int afterMinutes,
                            @Value("${app.stock-recovery.batch-size:50}") int batchSize) {
        this.orderRepository = orderRepository;
        this.orderInventoryService = orderInventoryService;
        this.advisoryLock = advisoryLock;
        this.afterMinutes = afterMinutes;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${app.stock-recovery.interval-ms:600000}",
            initialDelayString = "${app.stock-recovery.interval-ms:600000}")
    public void recoverMissedStock() {
        advisoryLock.runExclusively(ADVISORY_LOCK_KEY, "barrido de stock", this::runOnce);
    }

    private void runOnce() {
        Instant notAfter = Instant.now().minus(Duration.ofMinutes(afterMinutes));
        List<Order> missed = orderRepository.findPaidWithoutStockApplied(notAfter, PageRequest.of(0, batchSize));
        if (missed.isEmpty()) {
            return;
        }

        log.warn("Barrido de stock: {} órdenes pagadas sin descontar (más de {} min desde el pago)",
                missed.size(), afterMinutes);
        for (Order order : missed) {
            recoverOne(order);
        }
    }

    private void recoverOne(Order order) {
        try {
            // Cada orden con el tenant de SU tienda: un job no tiene petición, así que nadie lo ha puesto
            // por él. Mismo patrón que AbandonedOrderJob.
            TenantContext.runWithin(order.getStore(), () -> orderInventoryService.recoverMissedStock(order.getId()));
        } catch (Exception e) {
            // Una orden que revienta no puede llevarse por delante a las demás de la tanda.
            log.error("Fallo recuperando el stock de la orden order_id={} error={}", order.getId(), e.getMessage());
            Sentry.captureException(e);
        }
    }
}
