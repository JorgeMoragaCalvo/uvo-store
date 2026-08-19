package org.uvo.uvostore.service.order;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.uvo.uvostore.entity.order.Order;
import org.uvo.uvostore.entity.order.OrderStatusHistory;
import org.uvo.uvostore.entity.order.enums.FulfillmentStatus;
import org.uvo.uvostore.entity.order.enums.OrderStatus;
import org.uvo.uvostore.entity.order.enums.PaymentStatus;
import org.uvo.uvostore.repository.OrderRepository;

import java.time.Instant;
import java.util.NoSuchElementException;

@Service
public class AdminOrderServiceImpl implements AdminOrderService {

    private final OrderRepository orderRepository;
    private final AdminOrderQueryService adminOrderQueryService;

    public AdminOrderServiceImpl(OrderRepository orderRepository, AdminOrderQueryService adminOrderQueryService) {
        this.orderRepository = orderRepository;
        this.adminOrderQueryService = adminOrderQueryService;
    }

    @Override
    @Transactional
    public AdminOrderDetailDto markProcessing(Long orderId) {
        Order order = findOrThrow(orderId);
        order.setStatus(OrderStatus.PROCESSING);
        appendHistory(order, "Marcada como en proceso");
        orderRepository.save(order);
        return adminOrderQueryService.getById(orderId);
    }

    @Override
    @Transactional
    public AdminOrderDetailDto markShipped(Long orderId) {
        Order order = findOrThrow(orderId);
        order.setStatus(OrderStatus.SHIPPED);
        order.setFulfillmentStatus(FulfillmentStatus.FULFILLED);
        order.setShippedAt(Instant.now());
        appendHistory(order, "Marcada como enviada");
        orderRepository.save(order);
        return adminOrderQueryService.getById(orderId);
    }

    @Override
    @Transactional
    public AdminOrderDetailDto markDelivered(Long orderId) {
        Order order = findOrThrow(orderId);
        order.setStatus(OrderStatus.DELIVERED);
        order.setDeliveredAt(Instant.now());
        appendHistory(order, "Marcada como entregada");
        orderRepository.save(order);
        return adminOrderQueryService.getById(orderId);
    }

    @Override
    @Transactional
    public AdminOrderDetailDto cancelOrder(Long orderId) {
        Order order = findOrThrow(orderId);
        order.setStatus(OrderStatus.CANCELLED);
        appendHistory(order, "Orden cancelada");
        orderRepository.save(order);
        return adminOrderQueryService.getById(orderId);
    }

    @Override
    @Transactional
    public AdminOrderDetailDto updateStatus(Long orderId, String status) {
        Order order = findOrThrow(orderId);
        order.setStatus(OrderStatus.valueOf(status.toUpperCase()));
        appendHistory(order, "Estado actualizado manualmente");
        orderRepository.save(order);
        return adminOrderQueryService.getById(orderId);
    }

    @Override
    @Transactional
    public AdminOrderDetailDto updatePaymentStatus(Long orderId, String paymentStatus) {
        Order order = findOrThrow(orderId);
        order.setPaymentStatus(PaymentStatus.valueOf(paymentStatus.toUpperCase()));
        orderRepository.save(order);
        return adminOrderQueryService.getById(orderId);
    }

    @Override
    @Transactional
    public AdminOrderDetailDto saveTracking(Long orderId, String trackingNumber) {
        Order order = findOrThrow(orderId);
        order.setTrackingNumber(trackingNumber);
        order.setStatus(OrderStatus.SHIPPED);
        order.setFulfillmentStatus(FulfillmentStatus.FULFILLED);
        if (order.getShippedAt() == null) {
            order.setShippedAt(Instant.now());
        }
        appendHistory(order, "Número de seguimiento guardado");
        orderRepository.save(order);
        return adminOrderQueryService.getById(orderId);
    }

    private void appendHistory(Order order, String notes) {
        OrderStatusHistory history = new OrderStatusHistory();
        history.setOrder(order);
        history.setStatus(order.getStatus().name());
        history.setNotes(notes);
        order.getStatusHistory().add(history);
    }

    private Order findOrThrow(Long orderId) {
        return orderRepository.findById(orderId)
                .orElseThrow(() -> new NoSuchElementException("Order " + orderId + " not found"));
    }
}
