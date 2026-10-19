package org.uvo.uvostore.service.outbox;

import jakarta.mail.SendFailedException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailParseException;
import org.springframework.mail.MailSendException;
import org.uvo.uvostore.entity.customer.Customer;
import org.uvo.uvostore.entity.customer.enums.AccountStatus;
import org.uvo.uvostore.entity.order.Order;
import org.uvo.uvostore.entity.order.enums.OrderStatus;
import org.uvo.uvostore.entity.order.enums.PaymentStatus;
import org.uvo.uvostore.entity.outbox.OutboxMessage;
import org.uvo.uvostore.entity.outbox.enums.OutboxType;
import org.uvo.uvostore.entity.tenant.Store;
import org.uvo.uvostore.repository.OrderRepository;
import org.uvo.uvostore.service.customer.CustomerAccountsPolicy;
import org.uvo.uvostore.service.notification.EmailService;
import org.uvo.uvostore.service.url.StorePublicUrlResolver;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * PROD-13b. Los tres correos de una orden, entregados desde el outbox: cuándo salen, cuándo ya no tienen
 * sentido y cómo se clasifica un fallo del SMTP. Unitario: lo que se decide aquí no necesita base de datos.
 */
class OrderEmailHandlersTest {

    private final OrderRepository orderRepository = mock(OrderRepository.class);
    private final EmailService emailService = mock(EmailService.class);
    private final StorePublicUrlResolver publicUrls = mock(StorePublicUrlResolver.class);

    @Test
    @DisplayName("El acuse de recibo sale mientras el pago está pendiente")
    void thePlacedEmailGoesOutWhilePending() {
        Order order = order(PaymentStatus.PENDING, OrderStatus.PENDING);
        when(emailService.deliver(anyString(), anyString(), anyString())).thenReturn(EmailService.Delivery.SENT);

        OutboxResult result = placedHandler(true).handle(row(order, OutboxType.ORDER_PLACED_EMAIL));

        assertThat(result.outcome()).isEqualTo(OutboxResult.Outcome.DELIVERED);
        verify(emailService).deliver(eq("comprador@test.local"), eq("Recibimos tu pedido ORD-MAIL"), anyString());
    }

    @Test
    @DisplayName("Si la orden ya se pagó cuando toca enviarlo, el acuse sobra y no sale")
    void thePlacedEmailIsSkippedOnceTheOrderIsPaid() {
        // Si saliera después de la confirmación, le diría "te confirmaremos la compra" a quien ya recibió
        // las gracias por ella.
        Order order = order(PaymentStatus.PAID, OrderStatus.PROCESSING);

        OutboxResult result = placedHandler(true).handle(row(order, OutboxType.ORDER_PLACED_EMAIL));

        assertThat(result.outcome()).isEqualTo(OutboxResult.Outcome.SKIPPED);
        verifyNoInteractions(emailService);
    }

    @Test
    @DisplayName("No se dan las gracias por una compra que se canceló antes de enviar el correo")
    void theConfirmationIsSkippedForACancelledOrder() {
        Order order = order(PaymentStatus.PAID, OrderStatus.CANCELLED);
        OrderConfirmationEmailHandler handler = new OrderConfirmationEmailHandler(composer(true), emailService);

        OutboxResult result = handler.handle(row(order, OutboxType.ORDER_CONFIRMATION_EMAIL));

        assertThat(result.outcome()).isEqualTo(OutboxResult.Outcome.SKIPPED);
        verifyNoInteractions(emailService);
    }

    @Test
    @DisplayName("Sin SMTP se omite con una marca que permite reencolarlo después")
    void withoutSmtpTheEmailIsSkippedWithAMark() {
        Order order = order(PaymentStatus.PENDING, OrderStatus.PENDING);
        when(emailService.deliver(anyString(), anyString(), anyString())).thenReturn(EmailService.Delivery.NOT_CONFIGURED);

        OutboxResult result = placedHandler(true).handle(row(order, OutboxType.ORDER_PLACED_EMAIL));

        assertThat(result.outcome()).isEqualTo(OutboxResult.Outcome.SKIPPED);
        assertThat(result.detail()).isEqualTo("smtp-not-configured");
    }

    @Test
    @DisplayName("Un SMTP caído o que rechaza la autenticación se reintenta")
    void aTransientSmtpFailureIsRetried() {
        Order order = order(PaymentStatus.PENDING, OrderStatus.PENDING);
        when(emailService.deliver(anyString(), anyString(), anyString()))
                .thenThrow(new MailSendException("Connection refused"))
                .thenThrow(new MailAuthenticationException("535 Authentication failed"));

        assertThat(placedHandler(true).handle(row(order, OutboxType.ORDER_PLACED_EMAIL)).outcome())
                .isEqualTo(OutboxResult.Outcome.RETRYABLE);
        assertThat(placedHandler(true).handle(row(order, OutboxType.ORDER_PLACED_EMAIL)).outcome())
                .isEqualTo(OutboxResult.Outcome.RETRYABLE);
    }

    @Test
    @DisplayName("Una dirección que el servidor rechaza, o un mensaje mal formado, no se reintentan")
    void aRejectedAddressOrMalformedMessageFailsAtOnce() {
        Order order = order(PaymentStatus.PENDING, OrderStatus.PENDING);
        when(emailService.deliver(anyString(), anyString(), anyString()))
                .thenThrow(new MailSendException(Map.of("mensaje", new SendFailedException("Invalid Addresses"))))
                .thenThrow(new MailParseException("dirección ilegible"));

        assertThat(placedHandler(true).handle(row(order, OutboxType.ORDER_PLACED_EMAIL)).outcome())
                .isEqualTo(OutboxResult.Outcome.PERMANENT);
        assertThat(placedHandler(true).handle(row(order, OutboxType.ORDER_PLACED_EMAIL)).outcome())
                .isEqualTo(OutboxResult.Outcome.PERMANENT);
    }

    @Test
    @DisplayName("PROD-07: con las cuentas apagadas no sale la invitación, aunque estuviera encolada")
    void noInvitationWhileAccountsAreDisabled() {
        // El caso que importa es el de la fila vieja: un cliente con token y sin contraseña, que pasa las
        // tres condiciones del guarda, encolado antes de apagar el interruptor. La bandera se mira también
        // al enviar, y antes de leer nada.
        Order order = order(PaymentStatus.PENDING, OrderStatus.PENDING);
        Customer customer = new Customer();
        customer.setId(7L);
        customer.setEmail("invitado@test.local");
        customer.setFirstName("Invitado");
        customer.setAccountStatus(AccountStatus.INVITED);
        customer.setInvitationToken("un-token-de-antes-del-interruptor");
        order.setCustomer(customer);
        CustomerInvitationEmailHandler handler = new CustomerInvitationEmailHandler(composer(false), emailService);

        OutboxResult result = handler.handle(row(order, OutboxType.CUSTOMER_INVITATION_EMAIL));

        assertThat(result.outcome()).isEqualTo(OutboxResult.Outcome.SKIPPED);
        // verifyNoInteractions y no verify(never()).deliver(...): afirma que no se llegó siquiera a mirar el
        // servicio de correo, ni la orden, ni la URL de la tienda.
        verifyNoInteractions(emailService);
        verifyNoInteractions(publicUrls);
        verifyNoInteractions(orderRepository);
    }

    private OrderPlacedEmailHandler placedHandler(boolean accountsEnabled) {
        return new OrderPlacedEmailHandler(composer(accountsEnabled), emailService);
    }

    private OrderEmailComposer composer(boolean accountsEnabled) {
        return new OrderEmailComposer(orderRepository, publicUrls, new CustomerAccountsPolicy(accountsEnabled));
    }

    private OutboxMessage row(Order order, OutboxType type) {
        when(orderRepository.findById(order.getId())).thenReturn(Optional.of(order));
        return OutboxMessage.builder().id(1L).order(order).type(type).dedupKey(type + ":1").build();
    }

    private static Order order(PaymentStatus paymentStatus, OrderStatus status) {
        Store store = new Store();
        store.setId(1L);
        store.setName("Tienda");
        store.setSlug("tienda");

        Order order = new Order();
        order.setId(1L);
        order.setOrderNumber("ORD-MAIL");
        order.setStore(store);
        order.setCustomerEmail("comprador@test.local");
        order.setCustomerFirstName("Comprador");
        order.setTotal(BigDecimal.valueOf(1000));
        order.setPaymentStatus(paymentStatus);
        order.setStatus(status);
        order.setItems(new ArrayList<>());
        return order;
    }
}
