package org.uvo.uvostore.service.order;

import com.stripe.Stripe;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.exception.StripeException;
import com.stripe.model.Event;
import com.stripe.model.PaymentIntent;
import com.stripe.model.StripeObject;
import com.stripe.model.checkout.Session;
import com.stripe.net.Webhook;
import com.stripe.param.checkout.SessionCreateParams;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.uvo.uvostore.entity.order.Order;
import org.uvo.uvostore.entity.order.OrderItem;
import org.uvo.uvostore.entity.order.enums.PaymentStatus;
import org.uvo.uvostore.repository.OrderRepository;
import org.uvo.uvostore.repository.SettingRepository;

import java.math.BigDecimal;
import java.util.NoSuchElementException;
import java.util.Optional;

// Ports app/Services/StripeService.php + Api/PaymentController.php. The "reuse a Stripe Customer
// tied to an authenticated admin User" branch in the Laravel version doesn't have an equivalent
// here — storefront checkout is driven by the guest/storefront Customer entity, which (unlike
// User) has no stripe_customer_id column, so every session is created guest-style with
// order/customer details passed as PaymentIntent metadata instead.
@Service
public class PaymentServiceImpl implements PaymentService {

    private final OrderRepository orderRepository;
    private final SettingRepository settingRepository;
    private final OrderStatusService orderStatusService;
    private final String fallbackSecretKey;
    private final String fallbackWebhookSecret;
    private final String defaultCurrency;
    private final String frontendUrl;

    public PaymentServiceImpl(
            OrderRepository orderRepository,
            SettingRepository settingRepository,
            OrderStatusService orderStatusService,
            @Value("${stripe.secret-key}") String fallbackSecretKey,
            @Value("${stripe.webhook-secret}") String fallbackWebhookSecret,
            @Value("${stripe.default-currency}") String defaultCurrency,
            @Value("${app.frontend-url}") String frontendUrl) {
        this.orderRepository = orderRepository;
        this.settingRepository = settingRepository;
        this.orderStatusService = orderStatusService;
        this.fallbackSecretKey = fallbackSecretKey;
        this.fallbackWebhookSecret = fallbackWebhookSecret;
        this.defaultCurrency = defaultCurrency;
        this.frontendUrl = frontendUrl;
    }

    @Override
    @Transactional
    public CheckoutSessionResult createCheckoutSession(Long orderId, String successUrl, String cancelUrl) {
        applyApiKey();

        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new NoSuchElementException("Order " + orderId + " not found"));

        if (order.getPaymentStatus() != PaymentStatus.PENDING) {
            throw new IllegalStateException("Esta orden ya fue procesada");
        }

        String currency = settingValue("currency", defaultCurrency);
        SessionCreateParams.Builder builder = SessionCreateParams.builder()
                .addPaymentMethodType(SessionCreateParams.PaymentMethodType.CARD)
                .setMode(SessionCreateParams.Mode.PAYMENT)
                .setSuccessUrl(successUrl != null ? successUrl : frontendUrl + "/order/success?session_id={CHECKOUT_SESSION_ID}")
                .setCancelUrl(cancelUrl != null ? cancelUrl : frontendUrl + "/checkout?canceled=1")
                .setClientReferenceId(order.getOrderNumber())
                .putMetadata("order_id", String.valueOf(order.getId()))
                .putMetadata("order_number", order.getOrderNumber())
                .setCustomerEmail(order.getCustomerEmail());

        for (OrderItem item : order.getItems()) {
            builder.addLineItem(lineItem(currency, item.getProductName(), item.getProductSku(), item.getPrice(), item.getQuantity()));
        }
        if (order.getShippingCost() != null && order.getShippingCost().signum() > 0) {
            builder.addLineItem(lineItem(currency, "Envío", "Costo de envío a domicilio", order.getShippingCost(), 1));
        }
        if (order.getTaxAmount() != null && order.getTaxAmount().signum() > 0) {
            builder.addLineItem(lineItem(currency, "IVA", "Impuesto al Valor Agregado", order.getTaxAmount(), 1));
        }

        builder.setPaymentIntentData(SessionCreateParams.PaymentIntentData.builder()
                .putMetadata("order_id", String.valueOf(order.getId()))
                .putMetadata("order_number", order.getOrderNumber())
                .putMetadata("customer_name", order.getCustomerFirstName() + " " + order.getCustomerLastName())
                .putMetadata("customer_phone", order.getCustomerPhone() == null ? "" : order.getCustomerPhone())
                .build());

        try {
            Session session = Session.create(builder.build());
            order.setStripeCheckoutSessionId(session.getId());
            orderRepository.save(order);
            return new CheckoutSessionResult(session.getId(), session.getUrl());
        } catch (StripeException e) {
            throw new IllegalStateException("Error al crear sesión de pago: " + e.getMessage(), e);
        }
    }

    @Override
    @Transactional
    public PaymentVerificationResult verifyPayment(String sessionId) {
        applyApiKey();

        Session session;
        try {
            session = Session.retrieve(sessionId);
        } catch (StripeException e) {
            throw new IllegalStateException("Error al verificar pago: " + e.getMessage(), e);
        }

        Order order = orderRepository.findByStripeCheckoutSessionId(session.getId())
                .orElseThrow(() -> new NoSuchElementException("Orden no encontrada"));

        if ("paid".equals(session.getPaymentStatus()) && order.getPaymentStatus() == PaymentStatus.PENDING) {
            order = orderStatusService.markPaid(order.getId(), session.getPaymentIntent());
        }

        return new PaymentVerificationResult(session.getPaymentStatus(), order.getId(), order.getOrderNumber(), order.getPaymentStatus().name());
    }

    @Override
    @Transactional
    public void handleWebhook(String payload, String signatureHeader) {
        applyApiKey();
        String webhookSecret = settingValue("stripe_webhook_secret", fallbackWebhookSecret);

        Event event;
        try {
            event = Webhook.constructEvent(payload, signatureHeader, webhookSecret);
        } catch (SignatureVerificationException e) {
            throw new IllegalStateException("Firma de webhook inválida", e);
        }

        switch (event.getType()) {
            case "checkout.session.completed" -> deserialize(event, Session.class).ifPresent(session ->
                    orderRepository.findByStripeCheckoutSessionId(session.getId())
                            .filter(order -> order.getPaymentStatus() == PaymentStatus.PENDING)
                            .ifPresent(order -> orderStatusService.markPaid(order.getId(), session.getPaymentIntent())));
            case "checkout.session.expired" -> deserialize(event, Session.class).ifPresent(session ->
                    orderRepository.findByStripeCheckoutSessionId(session.getId())
                            .ifPresent(order -> orderStatusService.markCancelled(order.getId())));
            case "payment_intent.succeeded" -> deserialize(event, PaymentIntent.class).ifPresent(intent ->
                    orderRepository.findByStripePaymentIntentId(intent.getId())
                            .filter(order -> order.getPaymentStatus() == PaymentStatus.PENDING)
                            .ifPresent(order -> orderStatusService.markPaid(order.getId(), intent.getId())));
            case "payment_intent.payment_failed" -> deserialize(event, PaymentIntent.class).ifPresent(intent ->
                    orderRepository.findByStripePaymentIntentId(intent.getId())
                            .ifPresent(order -> orderStatusService.markPaymentFailed(order.getId())));
            case "payment_intent.canceled" -> deserialize(event, PaymentIntent.class).ifPresent(intent ->
                    orderRepository.findByStripePaymentIntentId(intent.getId())
                            .ifPresent(order -> orderStatusService.markCancelled(order.getId())));
            default -> {
                // No-op — matches PaymentController::webhook()'s switch, which ignores unhandled types.
            }
        }
    }

    @SuppressWarnings("unchecked")
    private <T extends StripeObject> Optional<T> deserialize(Event event, Class<T> type) {
        return event.getDataObjectDeserializer().getObject().filter(type::isInstance).map(obj -> (T) obj);
    }

    private SessionCreateParams.LineItem lineItem(String currency, String name, String description, BigDecimal amount, int quantity) {
        return SessionCreateParams.LineItem.builder()
                .setQuantity((long) quantity)
                .setPriceData(SessionCreateParams.LineItem.PriceData.builder()
                        .setCurrency(currency)
                        .setUnitAmount(amount.setScale(0, java.math.RoundingMode.HALF_UP).longValueExact())
                        .setProductData(SessionCreateParams.LineItem.PriceData.ProductData.builder()
                                .setName(name)
                                .setDescription(description)
                                .build())
                        .build())
                .build();
    }

    private void applyApiKey() {
        Stripe.apiKey = settingValue("stripe_secret_key", fallbackSecretKey);
    }

    private String settingValue(String key, String fallback) {
        return settingRepository.findBySettingKey(key).map(s -> s.getValue()).filter(v -> v != null && !v.isBlank()).orElse(fallback);
    }
}
