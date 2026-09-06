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
        Order order = findOrThrow(orderId);
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
        Order order = findOrThrow(orderId);
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
        Order order = findOrThrow(orderId);
        order.setPaymentStatus(PaymentStatus.FAILED);
        order.setStatus(OrderStatus.CANCELLED);
        appendHistory(order, OrderStatus.CANCELLED, "Sesión de pago expirada o cancelada");
        // C5: both are no-ops when the order never got paid (nothing was decremented, and the
        // guards inside handle the repeat case), so this is safe on any cancellation.
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

    private void reportAmountMismatch(Order order, BigDecimal amountPaid) {
        String detail = "Monto pagado no coincide: se recibieron " + (amountPaid == null ? "un importe desconocido" : amountPaid)
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

    private Order findOrThrow(Long orderId) {
        return orderRepository.findById(orderId)
                .orElseThrow(() -> new NoSuchElementException("Order " + orderId + " not found"));
    }
}
