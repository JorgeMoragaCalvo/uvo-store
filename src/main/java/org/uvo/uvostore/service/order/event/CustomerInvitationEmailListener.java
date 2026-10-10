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
import org.uvo.uvostore.entity.customer.Customer;
import org.uvo.uvostore.entity.order.Order;
import org.uvo.uvostore.repository.OrderRepository;
import org.uvo.uvostore.security.TenantContext;
import org.uvo.uvostore.service.notification.EmailService;
import org.uvo.uvostore.service.url.StorePublicUrlResolver;

import java.util.NoSuchElementException;

/**
 * F24. La invitación que nunca se enviaba.
 *
 * <p>El checkout ya generaba un {@code invitationToken}, una fecha y el estado {@code INVITED}
 * ({@code CustomerServiceImpl.markInvitedIfGuest}), pero ahí se acababa: no salía ningún correo y no
 * había endpoint que aceptara la invitación. El resultado es que el comprador quedaba con <b>el correo
 * quemado</b> en esa tienda — {@code customerRegister} lo rechaza porque la fila ya existe y
 * {@code customerLogin} exige {@code ACTIVE} con contraseña. Este oyente es la mitad que faltaba.
 *
 * <p>Oyente aparte y no un párrafo dentro del acuse de recibo
 * ({@link OrderPlacedEmailListener}) porque son dos mensajes con significados distintos: ese sale
 * siempre, y este solo cuando el comprador no tenía cuenta.
 *
 * <p>Mismo andamiaje que los otros oyentes AFTER_COMMIT: el executor del correo (R1), transacción
 * propia, la tienda aplicada a mano porque no cruza al hilo del pool, y los fallos registrados aquí
 * porque en un hilo de pool no hay a quién relanzarlos.
 */
@Component
public class CustomerInvitationEmailListener {

    private static final Logger log = LoggerFactory.getLogger(CustomerInvitationEmailListener.class);

    private final OrderRepository orderRepository;
    private final EmailService emailService;
    private final StorePublicUrlResolver publicUrls;

    public CustomerInvitationEmailListener(OrderRepository orderRepository, EmailService emailService,
                                            StorePublicUrlResolver publicUrls) {
        this.orderRepository = orderRepository;
        this.emailService = emailService;
        this.publicUrls = publicUrls;
    }

    @Async(AsyncConfig.MAIL_EXECUTOR)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onOrderPlaced(OrderPlacedEvent event) {
        try {
            Order order = orderRepository.findById(event.orderId())
                    .orElseThrow(() -> new NoSuchElementException("Order " + event.orderId() + " not found"));
            Customer customer = order.getCustomer();
            // Solo al invitado al que el checkout acaba de generarle una invitación. Un cliente que ya
            // tenía cuenta no recibe nada, y quien compra por segunda vez tampoco: markInvitedIfGuest no
            // regenera el token, así que a partir del segundo pedido esto no encuentra nada que mandar.
            if (customer == null || customer.getInvitationToken() == null || customer.getPassword() != null) {
                return;
            }
            TenantContext.runWithin(order.getStore(), () -> emailService.send(customer.getEmail(),
                    "Crea tu cuenta en " + order.getStore().getName(), body(customer, order)));
        } catch (Exception e) {
            log.error("Error enviando la invitación de cuenta order_id={} error={}", event.orderId(), e.getMessage());
            Sentry.captureException(e);
        }
    }

    /**
     * Visible en el paquete para poder afirmar sobre el cuerpo sin enviar nada, igual que
     * {@code PaymentServiceImpl.buildSessionParams} y {@code MercadoPagoServiceImpl.buildPreferenceRequest}.
     * En los tests no hay SMTP —{@code EmailServiceImpl} registra y omite el envío— así que esta es la
     * única forma de comprobar que el enlace lleva el token.
     */
    String body(Customer customer, Order order) {
        return "Hola " + customer.getFirstName() + ",\n\n"
                + "Compraste en " + order.getStore().getName() + " sin tener cuenta (pedido "
                + order.getOrderNumber() + "). Si quieres crear una con este correo y revisar tus datos, "
                + "elige una contraseña aquí:\n\n"
                // PROD-04: la tienda sale de la orden, no de la petición ni de TenantContext — esto corre
                // en un hilo del pool de correo después del commit, donde no hay ninguna de las dos.
                + publicUrls.storefrontUrl(order.getStore(), "/cuenta/activar?token=" + customer.getInvitationToken()) + "\n\n"
                + "El enlace es de un solo uso. Si no quieres cuenta, puedes ignorar este correo: tu pedido "
                + "sigue su curso igual.";
    }
}
