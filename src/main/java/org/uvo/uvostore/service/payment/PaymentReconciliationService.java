package org.uvo.uvostore.service.payment;

import io.sentry.Sentry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.uvo.uvostore.entity.order.Order;
import org.uvo.uvostore.entity.order.enums.PaymentMethodType;
import org.uvo.uvostore.entity.order.enums.PaymentStatus;
import org.uvo.uvostore.repository.OrderRepository;
import org.uvo.uvostore.security.TenantContext;
import org.uvo.uvostore.service.order.OrderStatusServiceImpl;
import org.uvo.uvostore.service.order.PaymentService;
import org.uvo.uvostore.service.order.PaymentVerificationResult;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * G1. Un pago que la pasarela cobró pero cuyo webhook nunca llegó dejaba la orden en
 * {@code PENDING} para siempre: nadie volvía a mirarla, así que el cliente había pagado y la tienda
 * no lo sabía. Esto va a preguntarle a la pasarela, cada cierto tiempo, qué pasó realmente con las
 * órdenes que llevan un rato sin resolverse.
 *
 * <p><b>Pregunta, no cobra.</b> La diferencia importa: Webpay usa {@code status()} y nunca
 * {@code commit()} —confirmar aquí una transacción que el cliente abandonó le cobraría—, y el paso a
 * {@code PAID} siempre va por {@code orderStatusService.markPaid}, que es idempotente y verifica el
 * monto desde M4. Por eso este trabajo sí va activado por defecto, al revés que
 * {@code PosNotificationRetryJob}: aquí no hay documentos tributarios que duplicar.
 *
 * <p>La otra mitad es la alerta: una orden que sigue pendiente pasadas
 * {@code app.reconciliation.stale-hours} ya no es un webhook que se retrasa, es algo que hay que
 * mirar a mano.
 */
@Component
@ConditionalOnProperty(name = "app.reconciliation.enabled", havingValue = "true", matchIfMissing = true)
public class PaymentReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(PaymentReconciliationService.class);

    /**
     * Clave del cerrojo consultivo. Es un número arbitrario pero fijo: lo único que importa es que
     * no lo use ningún otro {@code pg_advisory_lock} de la base.
     */
    private static final long ADVISORY_LOCK_KEY = 7_312_025_001L;

    private final OrderRepository orderRepository;
    private final PaymentService paymentService;
    private final WebpayService webpayService;
    private final MercadoPagoService mercadoPagoService;
    private final DataSource dataSource;
    private final int afterMinutes;
    private final int staleHours;
    private final int batchSize;

    public PaymentReconciliationService(OrderRepository orderRepository, PaymentService paymentService,
                                        WebpayService webpayService, MercadoPagoService mercadoPagoService,
                                        DataSource dataSource,
                                        @Value("${app.reconciliation.after-minutes:15}") int afterMinutes,
                                        @Value("${app.reconciliation.stale-hours:24}") int staleHours,
                                        @Value("${app.reconciliation.batch-size:50}") int batchSize) {
        this.orderRepository = orderRepository;
        this.paymentService = paymentService;
        this.webpayService = webpayService;
        this.mercadoPagoService = mercadoPagoService;
        this.dataSource = dataSource;
        this.afterMinutes = afterMinutes;
        this.staleHours = staleHours;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${app.reconciliation.interval-ms:600000}",
            initialDelayString = "${app.reconciliation.interval-ms:600000}")
    public void reconcilePendingPayments() {
        // El cerrojo se pide sobre una conexión propia y se mantiene toda la corrida: pg_advisory_lock
        // es de sesión, así que pedirlo y soltarlo por JdbcTemplate daría dos conexiones distintas del
        // pool y no protegería nada. Con app.storage.driver=s3 ya no vale suponer una sola instancia.
        Connection lockConnection;
        try {
            lockConnection = dataSource.getConnection();
        } catch (SQLException e) {
            log.error("No se pudo abrir conexión para el cerrojo de conciliación: {}", e.getMessage());
            Sentry.captureException(e);
            return;
        }

        try {
            if (!tryLock(lockConnection)) {
                log.debug("Otra instancia está conciliando pagos; esta corrida no hace nada");
                return;
            }
            try {
                runOnce();
            } finally {
                unlock(lockConnection);
            }
        } finally {
            closeQuietly(lockConnection);
        }
    }

    private void runOnce() {
        Instant notAfter = Instant.now().minus(Duration.ofMinutes(afterMinutes));
        List<Order> pending = orderRepository.findPendingPaymentsToReconcile(
                notAfter, OrderStatusServiceImpl.AMOUNT_MISMATCH_PREFIX + "%", PageRequest.of(0, batchSize));
        if (pending.isEmpty()) {
            return;
        }

        log.info("Conciliando el pago de {} órdenes pendientes", pending.size());
        Instant staleBefore = Instant.now().minus(Duration.ofHours(staleHours));
        for (Order order : pending) {
            reconcileOne(order, staleBefore);
        }
    }

    private void reconcileOne(Order order, Instant staleBefore) {
        if (order.getPaymentMethod() == PaymentMethodType.MANUAL || order.getPaymentMethod() == null) {
            // No hay pasarela contra la que conciliar: una transferencia o un pago contra entrega los
            // confirma una persona, y seguir pendiente es su estado normal mientras tanto. Tampoco
            // alerta, por lo mismo. La query no filtra por método, así que el descarte va aquí.
            return;
        }

        AtomicBoolean resolved = new AtomicBoolean(false);
        try {
            // Cada orden con el tenant de SU tienda: los reconcile de Webpay y MercadoPago llaman a
            // TenantContext.requireStoreId() para leer las credenciales, y un hilo del pool que se
            // quedara con el tenant anterior cobraría contra las credenciales de otra tienda.
            TenantContext.runWithin(order.getStore(), () -> resolved.set(askGateway(order)));
        } catch (Exception e) {
            // Una orden que revienta no puede llevarse por delante a las demás de la tanda.
            log.error("Fallo conciliando el pago order_id={} error={}", order.getId(), e.getMessage());
            Sentry.captureException(e);
        }

        if (!resolved.get() && order.getCreatedAt() != null && order.getCreatedAt().isBefore(staleBefore)) {
            alertStale(order);
        }
    }

    /**
     * @return true si la pasarela resolvió el pago, es decir, si la orden quedó pagada. False
     *         significa "sigue sin pagarse", que es un resultado legítimo: el cliente pudo
     *         abandonar el pago.
     */
    private boolean askGateway(Order order) {
        return switch (order.getPaymentMethod()) {
            case STRIPE -> {
                String sessionId = order.getStripeCheckoutSessionId();
                if (sessionId == null || sessionId.isBlank()) {
                    yield false;
                }
                PaymentVerificationResult result = paymentService.verifyPayment(sessionId);
                yield PaymentStatus.PAID.name().equals(result.orderPaymentStatus());
            }
            case WEBPAY -> webpayService.reconcile(order.getId());
            case MERCADOPAGO -> mercadoPagoService.reconcile(order.getId());
            // Descartado antes de llegar aquí, pero el switch sobre enum tiene que ser exhaustivo.
            case MANUAL -> false;
        };
    }

    private void alertStale(Order order) {
        String detail = "Orden con el pago sin resolver desde hace más de " + staleHours + " h";
        log.error("{} order_id={} order_number={} método={}",
                detail, order.getId(), order.getOrderNumber(), order.getPaymentMethod());
        // Sentry agrupa por huella, así que la misma orden en corridas sucesivas no inunda: se
        // acumula en un único issue con su contador.
        Sentry.captureMessage(detail + " [order_number=" + order.getOrderNumber() + "]");
    }

    private boolean tryLock(Connection connection) {
        try (PreparedStatement statement = connection.prepareStatement("select pg_try_advisory_lock(?)")) {
            statement.setLong(1, ADVISORY_LOCK_KEY);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() && rs.getBoolean(1);
            }
        } catch (SQLException e) {
            log.error("No se pudo pedir el cerrojo de conciliación: {}", e.getMessage());
            Sentry.captureException(e);
            return false;
        }
    }

    private void unlock(Connection connection) {
        try (PreparedStatement statement = connection.prepareStatement("select pg_advisory_unlock(?)")) {
            statement.setLong(1, ADVISORY_LOCK_KEY);
            statement.execute();
        } catch (SQLException e) {
            // Cerrar la conexión suelta igualmente los cerrojos de sesión, así que esto no deja el
            // trabajo bloqueado para siempre; se registra porque no debería pasar.
            log.warn("No se pudo soltar el cerrojo de conciliación: {}", e.getMessage());
        }
    }

    private void closeQuietly(Connection connection) {
        try {
            connection.close();
        } catch (SQLException e) {
            log.warn("No se pudo cerrar la conexión del cerrojo de conciliación: {}", e.getMessage());
        }
    }
}
