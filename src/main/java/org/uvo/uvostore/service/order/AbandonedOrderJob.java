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

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * F15. Suelta las reservas de las órdenes que nadie va a pagar.
 *
 * <p>El cupón se reserva al crear la orden —{@code claimUsage} sube {@code times_used} y
 * {@code recordUsage} escribe la fila—, y solo se libera si la orden se cancela o su pago falla. Una
 * orden abandonada no pasa por ninguna de las dos, así que retenía su uso <b>para siempre</b>: una
 * promoción de 100 usos se podía agotar con checkouts que no llegaron a cobrarse, sin una sola venta.
 *
 * <p>Y no las veía nadie: la conciliación de pagos ({@code findPendingPaymentsToReconcile}) exige que la
 * orden tenga id de pasarela, y las que se quedan por el camino —porque la llamada a la pasarela falló—
 * no lo tienen. Ni se revisaban ni se alertaban.
 *
 * <p><b>Activado por defecto</b>, al revés que el reintento del POS: aquí no hay documentos tributarios
 * que duplicar. Cancelar reutiliza {@code markCancelled}, que ya devuelve cupón y stock y es idempotente.
 *
 * <p>El plazo es generoso a propósito (24 h): una transferencia legítima tarda en llegar, y cancelar una
 * compra buena es bastante peor que retener un cupón un día más.
 */
@Component
@ConditionalOnProperty(name = "app.abandoned-orders.enabled", havingValue = "true", matchIfMissing = true)
public class AbandonedOrderJob {

    private static final Logger log = LoggerFactory.getLogger(AbandonedOrderJob.class);

    private final OrderRepository orderRepository;
    private final OrderStatusService orderStatusService;
    private final int afterHours;
    private final int batchSize;

    public AbandonedOrderJob(OrderRepository orderRepository, OrderStatusService orderStatusService,
                             @Value("${app.abandoned-orders.after-hours:24}") int afterHours,
                             @Value("${app.abandoned-orders.batch-size:50}") int batchSize) {
        this.orderRepository = orderRepository;
        this.orderStatusService = orderStatusService;
        this.afterHours = afterHours;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${app.abandoned-orders.interval-ms:3600000}",
            initialDelayString = "${app.abandoned-orders.interval-ms:3600000}")
    public void cancelAbandonedOrders() {
        Instant notAfter = Instant.now().minus(Duration.ofHours(afterHours));
        List<Order> abandoned = orderRepository.findAbandonedPending(notAfter, PageRequest.of(0, batchSize));
        if (abandoned.isEmpty()) {
            return;
        }

        log.info("Cancelando {} órdenes pendientes abandonadas (más de {} h sin pagar)", abandoned.size(), afterHours);
        for (Order order : abandoned) {
            cancelOne(order);
        }
    }

    private void cancelOne(Order order) {
        try {
            // Cada orden con el tenant de SU tienda: un job no tiene petición, así que nadie lo ha puesto
            // por él. Mismo patrón que PosNotificationRetryJob.
            TenantContext.runWithin(order.getStore(), () -> orderStatusService.markCancelled(order.getId()));
        } catch (Exception e) {
            // Una orden que revienta no puede llevarse por delante a las demás de la tanda.
            log.error("Fallo cancelando orden abandonada order_id={} error={}", order.getId(), e.getMessage());
            Sentry.captureException(e);
        }
    }
}
