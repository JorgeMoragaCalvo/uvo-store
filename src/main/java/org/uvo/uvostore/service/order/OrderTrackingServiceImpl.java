package org.uvo.uvostore.service.order;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.uvo.uvostore.entity.order.Order;
import org.uvo.uvostore.repository.OrderRepository;

import java.util.NoSuchElementException;

@Service
public class OrderTrackingServiceImpl implements OrderTrackingService {

    private final OrderRepository orderRepository;

    public OrderTrackingServiceImpl(OrderRepository orderRepository) {
        this.orderRepository = orderRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public OrderTrackingDto track(String orderNumber) {
        Order order = orderRepository.findByOrderNumber(orderNumber)
                .orElseThrow(() -> new NoSuchElementException("Order " + orderNumber + " not found"));

        return new OrderTrackingDto(
                order.getOrderNumber(),
                order.getStatus().name().toLowerCase(),
                order.getPaymentStatus().name().toLowerCase(),
                order.getFulfillmentStatus().name().toLowerCase(),
                order.getTrackingNumber(),
                order.getTrackingUrl(),
                order.getTotal(),
                order.getItems().size(),
                order.getCreatedAt(),
                order.getShippedAt(),
                order.getDeliveredAt()
        );
    }
}
