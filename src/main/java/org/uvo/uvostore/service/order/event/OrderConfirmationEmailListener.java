package org.uvo.uvostore.service.order.event;

import io.sentry.Sentry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.uvo.uvostore.config.AsyncConfig;
import org.uvo.uvostore.entity.order.Order;
import org.uvo.uvostore.entity.order.OrderItem;
import org.uvo.uvostore.repository.OrderRepository;
import org.uvo.uvostore.security.TenantContext;
import org.uvo.uvostore.service.notification.EmailService;

import java.util.NoSuchElementException;

// Same AFTER_COMMIT / REQUIRES_NEW pattern as PosNotificationListener — a failed or skipped email
// (EmailService degrades gracefully when SMTP isn't configured) must never affect the order that's
// already been committed.
//
// R1: y tampoco puede hacerla esperar. Esto corría en el hilo de la petición, y hasta que se pusieron
// los timeouts de spring.mail.* el default de JavaMail era esperar para siempre: un relay que aceptaba
// la conexión y no contestaba iba dejando hilos de Tomcat colgados hasta agotar el pool y tumbar la
// tienda entera. El try/catch de abajo no protegía de eso — una espera infinita no lanza nada.
@Component
public class OrderConfirmationEmailListener {

    private static final Logger log = LoggerFactory.getLogger(OrderConfirmationEmailListener.class);

    private final OrderRepository orderRepository;
    private final EmailService emailService;

    public OrderConfirmationEmailListener(OrderRepository orderRepository, EmailService emailService) {
        this.orderRepository = orderRepository;
        this.emailService = emailService;
    }

    @Async(AsyncConfig.MAIL_EXECUTOR)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onOrderCompleted(OrderCompletedEvent event) {
        try {
            Order order = orderRepository.findById(event.orderId())
                    .orElseThrow(() -> new NoSuchElementException("Order " + event.orderId() + " not found"));
            // El tenant no cruza al hilo del executor: se toma de la orden, que es de donde viene.
            TenantContext.runWithin(order.getStore(), () -> emailService.send(order.getCustomerEmail(),
                    "Confirmación de tu pedido " + order.getOrderNumber(), body(order)));
        } catch (Exception e) {
            // G2, mismo criterio que en los otros dos listeners: no se relanza (la transacción ya se
            // confirmó), pero deja de morir en una línea de log que nadie mira.
            log.error("Error enviando confirmación de compra order_id={} error={}", event.orderId(), e.getMessage());
            Sentry.captureException(e);
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
