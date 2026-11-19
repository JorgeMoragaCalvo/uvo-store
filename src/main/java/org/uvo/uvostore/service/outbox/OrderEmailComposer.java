package org.uvo.uvostore.service.outbox;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.uvo.uvostore.entity.customer.Customer;
import org.uvo.uvostore.entity.order.Order;
import org.uvo.uvostore.entity.order.OrderItem;
import org.uvo.uvostore.entity.order.enums.OrderStatus;
import org.uvo.uvostore.entity.order.enums.PaymentMethodType;
import org.uvo.uvostore.entity.order.enums.PaymentStatus;
import org.uvo.uvostore.repository.OrderRepository;
import org.uvo.uvostore.service.customer.CustomerAccountsPolicy;
import org.uvo.uvostore.service.url.StorePublicUrlResolver;

/**
 * PROD-13b. Los tres correos de una orden, armados en una transacción de lectura corta: el envío va
 * después, fuera de ella, para no tener una conexión de Hikari tomada mientras responde el SMTP.
 *
 * <p>Son los cuerpos que antes vivían en {@code OrderPlacedEmailListener},
 * {@code OrderConfirmationEmailListener} y {@code CustomerInvitationEmailListener}. Lo nuevo es que cada uno
 * vuelve a comprobar, al enviarse, que sigue teniendo sentido: con el outbox, entre el hecho y el envío pueden
 * pasar segundos, o horas si el SMTP está caído.
 *
 * <p>Bean aparte de los handlers a propósito: {@code @Transactional} en un método de la misma clase que lo
 * llama no pasa por el proxy, y la transacción no se abriría.
 */
@Component
public class OrderEmailComposer {

    private final OrderRepository orderRepository;
    private final StorePublicUrlResolver publicUrls;
    private final CustomerAccountsPolicy accountsPolicy;
    private final org.uvo.uvostore.repository.BankTransferSettingsRepository bankTransfers;
    private final org.uvo.uvostore.service.report.ReportZone zone;

    public OrderEmailComposer(OrderRepository orderRepository, StorePublicUrlResolver publicUrls,
                              CustomerAccountsPolicy accountsPolicy,
                              org.uvo.uvostore.repository.BankTransferSettingsRepository bankTransfers,
                              org.uvo.uvostore.service.report.ReportZone zone) {
        this.orderRepository = orderRepository;
        this.publicUrls = publicUrls;
        this.accountsPolicy = accountsPolicy;
        this.bankTransfers = bankTransfers;
        this.zone = zone;
    }

    /**
     * F07. El acuse de recibo: "lo recibimos", no "gracias por tu compra". Existe porque quien paga por
     * transferencia no recibiría nada hasta que un administrador confirme el ingreso.
     *
     * <p>Solo mientras el pago siga pendiente. Si la orden ya se pagó cuando toca enviarlo —un pago con
     * tarjeta tarda menos que una vuelta del despachador—, sale la confirmación y este sobra: enviarlo después
     * le diría "te confirmaremos la compra" a quien ya recibió la confirmación.
     */
    @Transactional(readOnly = true)
    public EmailDraft orderPlaced(Long orderId) {
        Order order = orderRepository.findById(orderId).orElse(null);
        if (order == null) {
            return EmailDraft.skip("La orden ya no existe");
        }
        if (order.getPaymentStatus() != PaymentStatus.PENDING) {
            return EmailDraft.skip("La orden ya no está pendiente de pago (" + order.getPaymentStatus()
                    + "): el acuse de recibo sobra");
        }
        return EmailDraft.of(order.getCustomerEmail(), "Recibimos tu pedido " + order.getOrderNumber(), placedBody(order));
    }

    /**
     * F07. El correo de compra confirmada, que por eso se encola con el pago. Al enviarse vuelve a mirar que
     * la venta siga en pie: no se le dan las gracias por una compra que se canceló o se devolvió.
     */
    @Transactional(readOnly = true)
    public EmailDraft orderConfirmation(Long orderId) {
        Order order = orderRepository.findById(orderId).orElse(null);
        if (order == null) {
            return EmailDraft.skip("La orden ya no existe");
        }
        if (order.getPaymentStatus() != PaymentStatus.PAID
                || order.getStatus() == OrderStatus.CANCELLED || order.getStatus() == OrderStatus.REFUNDED) {
            return EmailDraft.skip("La orden ya no está pagada (pago " + order.getPaymentStatus()
                    + ", estado " + order.getStatus() + ")");
        }
        return EmailDraft.of(order.getCustomerEmail(), "Confirmación de tu pedido " + order.getOrderNumber(),
                confirmationBody(order));
    }

    /**
     * F24. La invitación a crear cuenta de quien compró como invitado.
     *
     * <p>PROD-07: con las cuentas diferidas no sale ningún correo que prometa una. Se comprueba aquí ADEMÁS de
     * al encolar, y no sobra: una fila encolada antes de apagar el interruptor no debe salir después. Va
     * antes de leer nada, así que con las cuentas apagadas no se toca ni la orden.
     */
    @Transactional(readOnly = true)
    public EmailDraft customerInvitation(Long orderId) {
        if (!accountsPolicy.isEnabled()) {
            return EmailDraft.skip("Cuentas de comprador deshabilitadas");
        }
        Order order = orderRepository.findById(orderId).orElse(null);
        if (order == null) {
            return EmailDraft.skip("La orden ya no existe");
        }
        Customer customer = order.getCustomer();
        // Solo a quien sigue con una invitación pendiente: si activó la cuenta entretanto, ya no hace falta.
        if (customer == null || customer.getInvitationToken() == null || customer.getPassword() != null) {
            return EmailDraft.skip("El comprador ya no tiene una invitación pendiente");
        }
        return EmailDraft.of(customer.getEmail(), "Crea tu cuenta en " + order.getStore().getName(),
                invitationBody(customer, order));
    }

    String placedBody(Order order) {
        StringBuilder sb = new StringBuilder();
        sb.append("Hola ").append(order.getCustomerFirstName()).append(",\n\n");
        sb.append("Recibimos tu pedido ").append(order.getOrderNumber()).append(". Este es el detalle:\n\n");
        appendItems(sb, order);
        sb.append("\nTotal: $").append(order.getTotal()).append("\n\n");
        if (order.getPaymentMethod() == PaymentMethodType.MANUAL) {
            appendBankTransfer(sb, order);
        } else {
            sb.append("Te confirmaremos la compra en cuanto se acredite el pago.");
        }
        return sb.toString();
    }

    /**
     * IMP-07. Cómo pagar una orden por transferencia: los datos de la tienda, el monto exacto, la referencia y hasta
     * cuándo. Es lo único que tiene el comprador para pagar después de cerrar la página de confirmación.
     */
    private void appendBankTransfer(StringBuilder sb, Order order) {
        var settings = bankTransfers.findByStoreId(order.getStore().getId()).orElse(null);
        if (settings == null || !settings.isEnabled()) {
            // La tienda dejó de ofrecerla después de crear la orden: no se inventan datos.
            sb.append("Queda pendiente de pago. La tienda te indicará cómo completarlo.");
            return;
        }
        java.time.Instant deadline = org.uvo.uvostore.service.payment.transfer.BankTransferService
                .deadline(order.getCreatedAt() == null ? java.time.Instant.now() : order.getCreatedAt(),
                        settings.getPaymentWindowHours());
        String when = java.time.format.DateTimeFormatter
                .ofPattern("d 'de' MMMM 'a las' HH:mm", java.util.Locale.forLanguageTag("es-CL"))
                .withZone(zone.zone()).format(deadline);
        sb.append("Para completar tu compra, transfiere $").append(order.getTotal()).append(" a:\n\n");
        sb.append("  Titular: ").append(settings.getHolderName()).append("\n");
        sb.append("  RUT: ").append(settings.getHolderRut()).append("\n");
        sb.append("  Banco: ").append(settings.getBankName()).append("\n");
        sb.append("  ").append(settings.getAccountType().label()).append(": ").append(settings.getAccountNumber())
                .append("\n");
        sb.append("  Referencia: ").append(order.getOrderNumber()).append("\n\n");
        sb.append("Envía el comprobante a ").append(settings.getNoticeEmail()).append(". ");
        if (settings.getInstructions() != null) {
            sb.append(settings.getInstructions()).append(" ");
        }
        sb.append("Tienes hasta el ").append(when).append(" para pagar; después el pedido se cancela. En cuanto "
                + "confirmemos la transferencia te enviamos la confirmación de compra.");
    }

    String confirmationBody(Order order) {
        StringBuilder sb = new StringBuilder();
        sb.append("Hola ").append(order.getCustomerFirstName()).append(",\n\n");
        sb.append("Gracias por tu compra. Este es el resumen de tu pedido ").append(order.getOrderNumber()).append(":\n\n");
        appendItems(sb, order);
        sb.append("\nTotal: $").append(order.getTotal()).append("\n\n");
        sb.append("Te avisaremos cuando tu pedido sea despachado.");
        return sb.toString();
    }

    /**
     * Visible en el paquete para poder afirmar sobre el cuerpo sin enviar nada, igual que
     * {@code PaymentServiceImpl.buildSessionParams} y {@code MercadoPagoServiceImpl.buildPreferenceRequest}.
     * En los tests no hay SMTP, así que esta es la única forma de comprobar que el enlace lleva el token.
     */
    String invitationBody(Customer customer, Order order) {
        return "Hola " + customer.getFirstName() + ",\n\n"
                + "Compraste en " + order.getStore().getName() + " sin tener cuenta (pedido "
                + order.getOrderNumber() + "). Si quieres crear una con este correo y revisar tus datos, "
                + "elige una contraseña aquí:\n\n"
                // PROD-04: la tienda sale de la orden, no de la petición ni de TenantContext — esto corre en el
                // despachador del outbox, donde no hay petición.
                + publicUrls.storefrontUrl(order.getStore(), "/cuenta/activar?token=" + customer.getInvitationToken()) + "\n\n"
                + "El enlace es de un solo uso. Si no quieres cuenta, puedes ignorar este correo: tu pedido "
                + "sigue su curso igual.";
    }

    private static void appendItems(StringBuilder sb, Order order) {
        for (OrderItem item : order.getItems()) {
            sb.append("- ").append(item.getProductName())
                    .append(" x").append(item.getQuantity())
                    .append(" — $").append(item.getSubtotal()).append("\n");
        }
    }
}
