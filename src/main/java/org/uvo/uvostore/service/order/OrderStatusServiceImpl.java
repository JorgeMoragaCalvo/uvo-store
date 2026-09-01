package org.uvo.uvostore.service.order;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.uvo.uvostore.entity.order.Order;
import org.uvo.uvostore.entity.order.OrderStatusHistory;
import org.uvo.uvostore.entity.order.enums.OrderStatus;
import org.uvo.uvostore.entity.order.enums.PaymentStatus;
import org.uvo.uvostore.repository.OrderRepository;
import org.uvo.uvostore.service.order.event.PaymentConfirmedEvent;

import java.util.NoSuchElementException;

@Service
public class OrderStatusServiceImpl implements OrderStatusService {

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
    public Order markPaid(Long orderId, String paymentReference) {
        Order order = findOrThrow(orderId);
        if (order.getPaymentStatus() != PaymentStatus.PENDING) {
            return order;
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
