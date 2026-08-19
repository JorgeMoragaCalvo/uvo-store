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

    public OrderStatusServiceImpl(OrderRepository orderRepository, ApplicationEventPublisher applicationEventPublisher) {
        this.orderRepository = orderRepository;
        this.applicationEventPublisher = applicationEventPublisher;
    }

    @Override
    @Transactional
    public Order markPaid(Long orderId, String stripePaymentIntentId) {
        Order order = findOrThrow(orderId);
        if (order.getPaymentStatus() != PaymentStatus.PENDING) {
            return order;
        }
        order.setStripePaymentIntentId(stripePaymentIntentId);
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
        return orderRepository.save(order);
    }

    @Override
    @Transactional
    public Order markCancelled(Long orderId) {
        Order order = findOrThrow(orderId);
        order.setPaymentStatus(PaymentStatus.FAILED);
        order.setStatus(OrderStatus.CANCELLED);
        appendHistory(order, OrderStatus.CANCELLED, "Sesión de pago expirada o cancelada");
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
