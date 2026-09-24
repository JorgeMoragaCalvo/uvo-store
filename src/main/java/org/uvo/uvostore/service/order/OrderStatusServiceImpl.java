package org.uvo.uvostore.service.order;

import io.sentry.Sentry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.uvo.uvostore.entity.order.Order;
import org.uvo.uvostore.entity.order.OrderStatusHistory;
import org.uvo.uvostore.entity.order.enums.OrderStatus;
import org.uvo.uvostore.entity.order.enums.PaymentStatus;
import org.uvo.uvostore.repository.OrderRepository;
import org.uvo.uvostore.service.order.event.PaymentConfirmedEvent;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.NoSuchElementException;

@Service
public class OrderStatusServiceImpl implements OrderStatusService {

    private static final Logger log = LoggerFactory.getLogger(OrderStatusServiceImpl.class);

    /**
     * G1. El comienzo de la nota que deja un descuadre de monto, extraído a constante porque ahora
     * tiene un segundo lector: {@code PaymentReconciliationService} lo usa para NO reintentar esas
     * órdenes — no esperan a la pasarela, esperan a una persona. Si el texto cambia aquí sin
     * cambiarlo allá, la conciliación las reintentaría en cada corrida y alertaría para siempre.
     */
    public static final String AMOUNT_MISMATCH_PREFIX = "Monto pagado no coincide";

    private final OrderRepository orderRepository;
    private final ApplicationEventPublisher applicationEventPublisher;
    private final OrderInventoryService orderInventoryService;

    public OrderStatusServiceImpl(OrderRepository orderRepository, ApplicationEventPublisher applicationEventPublisher,
                                  OrderInventoryService orderInventoryService) {
        this.orderRepository = orderRepository;
        this.applicationEventPublisher = applicationEventPublisher;
        this.orderInventoryService = orderInventoryService;
    }

    @Override
    @Transactional
    public Order markPaid(Long orderId, String paymentReference, BigDecimal amountPaid) {
        // F03: con cerrojo, como los otros cambios de paymentStatus. Sin él, dos notificaciones
        // simultáneas del mismo pago leen las dos PENDING, las dos marcan pagado y las dos publican
        // PaymentConfirmedEvent.
        Order order = findForUpdateOrThrow(orderId);
        if (order.getPaymentStatus() != PaymentStatus.PENDING) {
            return order;
        }

        // M4: none of the three gateways compared what actually arrived against what was owed — they
        // each checked the payment's *status* and marked the order paid. The amount was available in
        // all three SDKs and simply never read.
        //
        // A mismatch leaves the order PENDING on purpose: money moved, but not the amount this order
        // is for, so a human has to look. Marking it paid anyway would hand the customer a fulfilled
        // order for the wrong price, and quietly.
        if (!amountMatches(order, amountPaid)) {
            reportAmountMismatch(order, amountPaid);
            return orderRepository.save(order);
        }
        // Generic column, populated regardless of gateway (Fase 2: Webpay/MercadoPago reuse this
        // same method). stripePaymentIntentId stays Stripe-only, kept for its existing lookups.
        order.setPaymentId(paymentReference);
        if (order.getPaymentMethod() == org.uvo.uvostore.entity.order.enums.PaymentMethodType.STRIPE) {
            order.setStripePaymentIntentId(paymentReference);
        }
        order.setPaymentStatus(PaymentStatus.PAID);
        order.setStatus(OrderStatus.PROCESSING);
        appendHistory(order, OrderStatus.PROCESSING, "Pago confirmado");
        Order saved = orderRepository.save(order);
        // Ports event(new PaymentConfirmed($order)) — StockDecrementListener reacts to this
        // AFTER_COMMIT, same as Laravel's queued listener running after the request completes.
        applicationEventPublisher.publishEvent(new PaymentConfirmedEvent(saved.getId()));
        return saved;
    }

    @Override
    @Transactional
    public Order markPaymentFailed(Long orderId) {
        Order order = findForUpdateOrThrow(orderId);
        if (!canFail(order, "Pago fallido")) {
            return order;
        }
        order.setPaymentStatus(PaymentStatus.FAILED);
        appendHistory(order, order.getStatus(), "Pago fallido");
        // C5: the coupon use was claimed at checkout, before the payment. Without giving it back, a
        // failed payment would burn it permanently.
        orderInventoryService.releaseCouponUsage(order);
        return orderRepository.save(order);
    }

    @Override
    @Transactional
    public Order markCancelled(Long orderId) {
        Order order = findForUpdateOrThrow(orderId);
        if (!canFail(order, "Sesión de pago expirada o cancelada")) {
            return order;
        }
        order.setPaymentStatus(PaymentStatus.FAILED);
        order.setStatus(OrderStatus.CANCELLED);
        appendHistory(order, OrderStatus.CANCELLED, "Sesión de pago expirada o cancelada");
        // C5: both are no-ops when the order never got paid (nothing was decremented, and the
        // guards inside handle the repeat case), so this is safe on any cancellation.
        orderInventoryService.restoreOrderStock(order);
        orderInventoryService.releaseCouponUsage(order);
        return orderRepository.save(order);
    }

    @Override
    @Transactional
    public Order markRefunded(Long orderId, String detail) {
        // El llamador (RefundService) ya toma este mismo cerrojo antes de mover el dinero; volver a
        // pedirlo dentro de su transacción no cuesta nada y deja el método a salvo por sí mismo.
        Order order = findForUpdateOrThrow(orderId);
        // Idempotente por el mismo motivo que markPaid: un segundo paso por aquí devolvería el stock
        // y el cupón otra vez, regalando unidades que nadie compró.
        if (order.getPaymentStatus() == PaymentStatus.REFUNDED) {
            return order;
        }

        order.setPaymentStatus(PaymentStatus.REFUNDED);
        order.setStatus(OrderStatus.REFUNDED);
        appendHistory(order, OrderStatus.REFUNDED, detail);
        // La compra entera se deshace, así que el inventario y el cupón vuelven — exactamente lo que
        // hace markCancelled. Los dos están guardados internamente y no hacen nada si no hay qué
        // devolver.
        orderInventoryService.restoreOrderStock(order);
        orderInventoryService.releaseCouponUsage(order);
        return orderRepository.save(order);
    }

    // Exact comparison, normalised to 2 decimals. No tolerance on purpose: CLP has no cents and
    // PaymentServiceImpl sends whole pesos, so any difference is a real discrepancy, not rounding.
    // A null amount means the caller couldn't determine one — treated as a mismatch rather than
    // waved through, since "we don't know what was paid" is not a reason to mark an order paid.
    private boolean amountMatches(Order order, BigDecimal amountPaid) {
        if (amountPaid == null) {
            return false;
        }
        return amountPaid.setScale(2, RoundingMode.HALF_UP)
                .compareTo(order.getTotal().setScale(2, RoundingMode.HALF_UP)) == 0;
    }

    /**
     * F03. Un pago confirmado no se deshace por un evento que llega tarde. A {@code FAILED} solo se
     * llega desde {@code PENDING}: {@code PAID} y {@code REFUNDED} no retroceden.
     *
     * <p><b>Por qué vive aquí y no en cada pasarela.</b> Webpay
     * ({@code WebpayServiceImpl}) y MercadoPago ({@code MercadoPagoServiceImpl}) ya comprobaban
     * {@code PENDING} antes de llamar; Stripe no. La invariante estaba replicada en tres sitios y
     * faltaba en uno — que es como se pierde siempre. Los guardas de esas dos pasarelas se quedan:
     * ahora son redundantes en vez de ser lo único que hay.
     *
     * <p><b>El caso real, que no es una carrera exótica.</b> Los handlers de
     * {@code payment_intent.payment_failed} y {@code payment_intent.canceled} buscan la orden por
     * {@code stripePaymentIntentId}, y ese campo <b>solo se escribe en markPaid</b> (unas líneas más
     * arriba). Sobre una orden pendiente no encuentran nada; la única orden que pueden encontrar es
     * una ya pagada. Con una tarjeta rechazada y un reintento exitoso en la misma sesión, Stripe
     * genera el evento de fallo <i>antes</i> que el de éxito y reintenta su entrega hasta tres días:
     * llegaba con la orden ya PAID y la tumbaba, liberando el cupón, con el dinero cobrado y sin que
     * la conciliación la volviera a mirar (solo mira PENDING).
     *
     * <p>No se calla cuando rechaza: una orden que ignora un webhook tiene que poder verse, por el
     * mismo motivo que un descuadre de monto — ver {@code reportAmountMismatch}.
     */
    private boolean canFail(Order order, String attempted) {
        if (order.getPaymentStatus() == PaymentStatus.PENDING) {
            return true;
        }
        String detail = "Evento de pago ignorado (" + attempted + "): la orden está en "
                + order.getPaymentStatus() + " y no vuelve atrás.";
        appendHistory(order, order.getStatus(), detail);
        orderRepository.save(order);
        log.warn("{} order_id={} order_number={}", detail, order.getId(), order.getOrderNumber());
        Sentry.captureMessage(detail + " [order_number=" + order.getOrderNumber() + "]");
        return false;
    }

    private void reportAmountMismatch(Order order, BigDecimal amountPaid) {
        String detail = AMOUNT_MISMATCH_PREFIX + ": se recibieron " + (amountPaid == null ? "un importe desconocido" : amountPaid)
                + " y la orden es de " + order.getTotal() + ". La orden queda pendiente para revisión manual.";
        // Visible where the operator already looks — the order's own history — and in Sentry, the
        // same pair OrderInventoryService uses for a failed stock decrement.
        appendHistory(order, order.getStatus(), detail);
        log.error("{} order_id={} order_number={}", detail, order.getId(), order.getOrderNumber());
        Sentry.captureMessage(detail + " [order_number=" + order.getOrderNumber() + "]");
    }

    private void appendHistory(Order order, OrderStatus status, String notes) {
        OrderStatusHistory history = new OrderStatusHistory();
        history.setOrder(order);
        history.setStatus(status.name());
        history.setNotes(notes);
        order.getStatusHistory().add(history);
    }

    /**
     * F03. Para lo que cambia {@code paymentStatus}, leer y escribir sin cerrojo no basta: dos
     * webhooks simultáneos leerían los dos el mismo estado y el candado de {@code canFail} no vería
     * nada raro. Con el bloqueo, el segundo espera y encuentra el estado que dejó el primero.
     */
    private Order findForUpdateOrThrow(Long orderId) {
        return orderRepository.findByIdForUpdate(orderId)
                .orElseThrow(() -> new NoSuchElementException("Order " + orderId + " not found"));
    }
}
