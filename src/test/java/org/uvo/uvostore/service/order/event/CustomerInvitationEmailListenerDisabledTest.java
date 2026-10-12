package org.uvo.uvostore.service.order.event;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.uvo.uvostore.entity.customer.Customer;
import org.uvo.uvostore.entity.customer.enums.AccountStatus;
import org.uvo.uvostore.entity.order.Order;
import org.uvo.uvostore.entity.tenant.Store;
import org.uvo.uvostore.repository.OrderRepository;
import org.uvo.uvostore.service.customer.CustomerAccountsPolicy;
import org.uvo.uvostore.service.notification.EmailService;
import org.uvo.uvostore.service.url.StorePublicUrlResolver;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * PROD-07. Con las cuentas diferidas no sale ningún correo que prometa una.
 *
 * <p><b>Unitario y sin contexto de Spring a propósito</b>, por lo que advierte el encabezado de
 * {@link CustomerInvitationEmailListenerTest}: el método es {@code @Async} + {@code AFTER_COMMIT} y su
 * {@code REQUIRES_NEW} escaparía al rollback del test, dejando basura en la base. Aquí el oyente se
 * construye a mano y se le llama directo, que es lo que permite afirmar sobre el envío sin envío.
 *
 * <p><b>El caso que importa es el de la fila vieja.</b> El cliente del fixture trae token y sigue sin
 * contraseña, así que pasa limpiamente las tres condiciones del guarda original: es la fila que quedó
 * {@code INVITED} antes del interruptor y que vuelve a comprar. Si la comprobación de la bandera
 * viviera solo en {@code markInvitedIfGuest}, esa fila mandaría el correo y el 404 del storefront la
 * recibiría — por eso la bandera se mira también aquí.
 */
class CustomerInvitationEmailListenerDisabledTest {

    private final OrderRepository orderRepository = mock(OrderRepository.class);
    private final EmailService emailService = mock(EmailService.class);
    private final StorePublicUrlResolver publicUrls = mock(StorePublicUrlResolver.class);

    @Test
    @DisplayName("Con las cuentas apagadas no se toca el servicio de correo, aunque haya invitación pendiente")
    void nothingIsSentWhileAccountsAreDisabled() {
        CustomerInvitationEmailListener listener = new CustomerInvitationEmailListener(
                orderRepository, emailService, publicUrls, new CustomerAccountsPolicy(false));
        when(orderRepository.findById(any())).thenReturn(Optional.of(orderWithPendingInvitation()));

        listener.onOrderPlaced(new OrderPlacedEvent(1L));

        // verifyNoInteractions y no verify(never()).send(...): el oyente envuelve todo en un try/catch
        // que registra y avisa a Sentry, así que un fallo al armar el cuerpo se vería como "no se envió".
        // Esto afirma que no se llegó siquiera a mirar el servicio.
        verifyNoInteractions(emailService);
        verifyNoInteractions(publicUrls);
    }

    private Order orderWithPendingInvitation() {
        Store store = new Store();
        store.setName("Tienda");
        store.setSlug("tienda");

        Customer customer = new Customer();
        customer.setId(7L);
        customer.setEmail("invitado@test.local");
        customer.setFirstName("Invitado");
        customer.setAccountStatus(AccountStatus.INVITED);
        customer.setInvitationToken("un-token-de-antes-del-interruptor");

        Order order = new Order();
        order.setId(1L);
        order.setOrderNumber("ORD-PROD07");
        order.setStore(store);
        order.setCustomer(customer);
        return order;
    }
}
