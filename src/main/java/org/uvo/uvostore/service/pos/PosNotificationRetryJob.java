package org.uvo.uvostore.service.pos;

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
 * G2. Reintenta las órdenes que intentaron notificarse a UvoPOS y no lo consiguieron. Hasta aquí una
 * plataforma SII caída durante diez minutos significaba perder esas órdenes para siempre: el
 * listener corre {@code AFTER_COMMIT}, se traga el error y no había nada que volviera a mirarlas.
 *
 * <p><b>Desactivado por defecto, y a propósito.</b> Reintentar solo es seguro si la plataforma
 * respeta la clave de idempotencia que manda {@link PosOrderPayloadMapper}, y ese contrato todavía
 * no está confirmado: si no la respeta, cada reintento emite un segundo documento tributario, que es
 * bastante peor que no reintentar. Se activa con {@code app.pos.retry.enabled=true} el día que la
 * plataforma confirme cómo trata {@code idempotency_key} / la cabecera {@code Idempotency-Key}.
 */
@Component
@ConditionalOnProperty(name = "app.pos.retry.enabled", havingValue = "true")
public class PosNotificationRetryJob {

    private static final Logger log = LoggerFactory.getLogger(PosNotificationRetryJob.class);

    private final OrderRepository orderRepository;
    private final PosOrderNotifier notifier;
    private final int maxAttempts;
    private final int minAgeMinutes;
    private final int batchSize;

    public PosNotificationRetryJob(OrderRepository orderRepository, PosOrderNotifier notifier,
                                   @Value("${app.pos.retry.max-attempts:5}") int maxAttempts,
                                   @Value("${app.pos.retry.min-age-minutes:10}") int minAgeMinutes,
                                   @Value("${app.pos.retry.batch-size:50}") int batchSize) {
        this.orderRepository = orderRepository;
        this.notifier = notifier;
        this.maxAttempts = maxAttempts;
        this.minAgeMinutes = minAgeMinutes;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${app.pos.retry.interval-ms:300000}",
            initialDelayString = "${app.pos.retry.interval-ms:300000}")
    public void retryPendingNotifications() {
        Instant notAfter = Instant.now().minus(Duration.ofMinutes(minAgeMinutes));
        List<Order> pending = orderRepository.findPendingPosNotification(maxAttempts, notAfter, PageRequest.of(0, batchSize));
        if (pending.isEmpty()) {
            return;
        }

        log.info("Reintentando notificación al POS de {} órdenes", pending.size());
        for (Order order : pending) {
            retryOne(order);
        }
    }

    private void retryOne(Order order) {
        try {
            // Cada orden con el tenant de SU tienda: un job no tiene petición, así que nadie lo ha
            // puesto por él. Ver TenantContext.runWithin.
            TenantContext.runWithin(order.getStore(), () -> notifier.notifyOrder(order.getId()));
        } catch (Exception e) {
            // Una orden que revienta no puede llevarse por delante a las demás de la tanda.
            log.error("Fallo reintentando notificación al POS order_id={} error={}", order.getId(), e.getMessage());
            Sentry.captureException(e);
        }
    }
}
