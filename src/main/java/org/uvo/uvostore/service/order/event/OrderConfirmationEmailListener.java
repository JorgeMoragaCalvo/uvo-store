package org.uvo.uvostore.service.order.event;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.uvo.uvostore.entity.order.Order;
import org.uvo.uvostore.entity.order.OrderItem;
import org.uvo.uvostore.repository.OrderRepository;
import org.uvo.uvostore.service.notification.EmailService;

import java.util.NoSuchElementException;

// Same AFTER_COMMIT / REQUIRES_NEW pattern as PosNotificationListener — a failed or skipped email
// (EmailService degrades gracefully when SMTP isn't configured) must never affect the order that's
// already been committed.
@Component
public class OrderConfirmationEmailListener {

    private static final Logger log = LoggerFactory.getLogger(OrderConfirmationEmailListener.class);

    private final OrderRepository orderRepository;
    private final EmailService emailService;

    public OrderConfirmationEmailListener(OrderRepository orderRepository, EmailService emailService) {
        this.orderRepository = orderRepository;
        this.emailService = emailService;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onOrderCompleted(OrderCompletedEvent event) {
        try {
            Order order = orderRepository.findById(event.orderId())
                    .orElseThrow(() -> new NoSuchElementException("Order " + event.orderId() + " not found"));
            emailService.send(order.getCustomerEmail(), "Confirmación de tu pedido " + order.getOrderNumber(), body(order));
        } catch (Exception e) {
            log.error("Error enviando confirmación de compra order_id={} error={}", event.orderId(), e.getMessage());
        }
    }

    private String body(Order order) {
        StringBuilder sb = new StringBuilder();
        sb.append("Hola ").append(order.getCustomerFirstName()).append(",\n\n");
        sb.append("Gracias por tu compra. Este es el resumen de tu pedido ").append(order.getOrderNumber()).append(":\n\n");
        for (OrderItem item : order.getItems()) {
            sb.append("- ").append(item.getProductName())
                    .append(" x").append(item.getQuantity())
                    .append(" — $").append(item.getSubtotal()).append("\n");
        }
        sb.append("\nTotal: $").append(order.getTotal()).append("\n\n");
        sb.append("Te avisaremos cuando tu pedido sea despachado.");
        return sb.toString();
    }
}
