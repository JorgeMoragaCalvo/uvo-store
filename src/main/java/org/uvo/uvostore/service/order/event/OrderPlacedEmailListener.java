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
import org.uvo.uvostore.entity.order.enums.PaymentMethodType;
import org.uvo.uvostore.repository.OrderRepository;
import org.uvo.uvostore.security.TenantContext;
import org.uvo.uvostore.service.notification.EmailService;

import java.util.NoSuchElementException;

/**
 * F07. El acuse de recibo del pedido: "lo recibimos", no "gracias por tu compra".
 *
 * <p>Existe porque mover el correo de confirmación al pago confirmado dejaba mudo al cliente que paga
 * por transferencia — el método principal de la tienda demo—, que no recibiría nada hasta que un
 * administrador confirme el ingreso, quizá al día siguiente. Callar ahí sería peor que el fallo que se
 * está arreglando.
 *
 * <p>La diferencia con {@link OrderConfirmationEmailListener} no es de plantilla, es de significado:
 * este no afirma que haya habido un pago, y cuando el método es MANUAL lo dice explícitamente.
 *
 * <p>Mismo andamiaje que los otros dos oyentes AFTER_COMMIT: el executor del correo (R1), transacción
 * propia, el tenant tomado de la orden porque no cruza al hilo del pool, y los fallos registrados aquí
 * porque en un hilo de pool no hay a quién relanzarlos.
 */
@Component
public class OrderPlacedEmailListener {

    private static final Logger log = LoggerFactory.getLogger(OrderPlacedEmailListener.class);

    private final OrderRepository orderRepository;
    private final EmailService emailService;

    public OrderPlacedEmailListener(OrderRepository orderRepository, EmailService emailService) {
        this.orderRepository = orderRepository;
        this.emailService = emailService;
    }

    @Async(AsyncConfig.MAIL_EXECUTOR)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onOrderPlaced(OrderPlacedEvent event) {
        try {
            Order order = orderRepository.findById(event.orderId())
                    .orElseThrow(() -> new NoSuchElementException("Order " + event.orderId() + " not found"));
            TenantContext.runWithin(order.getStore(), () -> emailService.send(order.getCustomerEmail(),
                    "Recibimos tu pedido " + order.getOrderNumber(), body(order)));
        } catch (Exception e) {
            log.error("Error enviando el acuse de recibo del pedido order_id={} error={}", event.orderId(), e.getMessage());
            Sentry.captureException(e);
        }
    }

    private String body(Order order) {
        StringBuilder sb = new StringBuilder();
        sb.append("Hola ").append(order.getCustomerFirstName()).append(",\n\n");
        sb.append("Recibimos tu pedido ").append(order.getOrderNumber()).append(". Este es el detalle:\n\n");
        for (OrderItem item : order.getItems()) {
            sb.append("- ").append(item.getProductName())
                    .append(" x").append(item.getQuantity())
                    .append(" — $").append(item.getSubtotal()).append("\n");
        }
        sb.append("\nTotal: $").append(order.getTotal()).append("\n\n");
        if (order.getPaymentMethod() == PaymentMethodType.MANUAL) {
            sb.append("Queda pendiente de pago: en cuanto confirmemos la transferencia te enviamos la "
                    + "confirmación de compra.");
        } else {
            sb.append("Te confirmaremos la compra en cuanto se acredite el pago.");
        }
        return sb.toString();
    }
}
