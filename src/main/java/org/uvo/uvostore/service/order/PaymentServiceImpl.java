package org.uvo.uvostore.service.order;

import com.stripe.exception.SignatureVerificationException;
import com.stripe.exception.StripeException;
import com.stripe.model.Event;
import com.stripe.model.PaymentIntent;
import com.stripe.model.StripeObject;
import com.stripe.model.checkout.Session;
import com.stripe.net.RequestOptions;
import com.stripe.net.Webhook;
import com.stripe.param.checkout.SessionCreateParams;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.uvo.uvostore.entity.order.Order;
import org.uvo.uvostore.entity.order.enums.PaymentStatus;
import org.uvo.uvostore.repository.OrderRepository;
import org.uvo.uvostore.repository.SettingRepository;
import org.uvo.uvostore.security.TenantContext;

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
        Order order = orderRepository.findById(orderId)
                .filter(o -> o.getStore().getId().equals(TenantContext.requireStoreId()))
                .orElseThrow(() -> new NoSuchElementException("Order " + orderId + " not found"));

        if (order.getPaymentStatus() != PaymentStatus.PENDING) {
            throw new IllegalStateException("Esta orden ya fue procesada");
        }

        SessionCreateParams params = buildSessionParams(order, successUrl, cancelUrl);

        try {
            Session session = Session.create(params, requestOptions());
            order.setStripeCheckoutSessionId(session.getId());
            orderRepository.save(order);
            return new CheckoutSessionResult(session.getId(), session.getUrl());
        } catch (StripeException e) {
            throw new IllegalStateException("Error al crear sesión de pago: " + e.getMessage(), e);
        }
    }

    // Package-visible so tests can assert the built params (line items, total) without making a
    // real Stripe API call.
    SessionCreateParams buildSessionParams(Order order, String successUrl, String cancelUrl) {
        String currency = settingValue("currency", defaultCurrency);

        // A single line item for order.getTotal() — the total already computed correctly on the
        // server (discount subtracted, tax not double-counted when prices are tax-inclusive; see
        // CartPricingServiceImpl) — instead of reconstructing items+shipping+tax separately here,
        // which previously dropped the discount entirely and double-charged tax whenever
        // prices_include_tax was on. This guarantees Stripe always charges exactly the order total.
        SessionCreateParams.LineItem totalLineItem = SessionCreateParams.LineItem.builder()
                .setQuantity(1L)
                .setPriceData(SessionCreateParams.LineItem.PriceData.builder()
                        .setCurrency(currency)
                        .setUnitAmount(order.getTotal().setScale(0, java.math.RoundingMode.HALF_UP).longValueExact())
                        .setProductData(SessionCreateParams.LineItem.PriceData.ProductData.builder()
                                .setName("Pedido " + order.getOrderNumber())
                                .setDescription(order.getItems().size() + " producto(s)")
                                .build())
                        .build())
                .build();

        return SessionCreateParams.builder()
                .addPaymentMethodType(SessionCreateParams.PaymentMethodType.CARD)
                .setMode(SessionCreateParams.Mode.PAYMENT)
                .setSuccessUrl(successUrl != null ? successUrl : frontendUrl + "/order/success?session_id={CHECKOUT_SESSION_ID}")
                .setCancelUrl(cancelUrl != null ? cancelUrl : frontendUrl + "/checkout?canceled=1")
                .setClientReferenceId(order.getOrderNumber())
                .putMetadata("order_id", String.valueOf(order.getId()))
                .putMetadata("order_number", order.getOrderNumber())
                .setCustomerEmail(order.getCustomerEmail())
                .addLineItem(totalLineItem)
                .setPaymentIntentData(SessionCreateParams.PaymentIntentData.builder()
                        .putMetadata("order_id", String.valueOf(order.getId()))
                        .putMetadata("order_number", order.getOrderNumber())
                        .putMetadata("customer_name", order.getCustomerFirstName() + " " + order.getCustomerLastName())
                        .putMetadata("customer_phone", order.getCustomerPhone() == null ? "" : order.getCustomerPhone())
                        .build())
                .build();
    }

    @Override
    @Transactional
    public PaymentVerificationResult verifyPayment(String sessionId) {
        Session session;
        try {
            session = Session.retrieve(sessionId, requestOptions());
        } catch (StripeException e) {
            throw new IllegalStateException("Error al verificar pago: " + e.getMessage(), e);
        }

        Order order = orderRepository.findByStripeCheckoutSessionId(session.getId())
                .orElseThrow(() -> new NoSuchElementException("Orden no encontrada"));

        if ("paid".equals(session.getPaymentStatus()) && order.getPaymentStatus() == PaymentStatus.PENDING) {
            // M4: amount_total comes back in the same unit createCheckoutSession sent (whole pesos
            // for CLP — see the setUnitAmount call above), so this compares like with like.
            order = orderStatusService.markPaid(order.getId(), session.getPaymentIntent(), stripeAmount(session.getAmountTotal()));
        }

        return new PaymentVerificationResult(session.getPaymentStatus(), order.getId(), order.getOrderNumber(), order.getPaymentStatus().name());
    }

    @Override
    @Transactional
    public void handleWebhook(String payload, String signatureHeader) {
        // Webhook.constructEvent only verifies the HMAC signature locally — it never calls the
        // Stripe API, so it needs no API key.
        String webhookSecret = encryptedSettingValue("stripe_webhook_secret", fallbackWebhookSecret);

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
                            .ifPresent(order -> orderStatusService.markPaid(order.getId(), session.getPaymentIntent(),
                                    stripeAmount(session.getAmountTotal()))));
            case "checkout.session.expired" -> deserialize(event, Session.class).ifPresent(session ->
                    orderRepository.findByStripeCheckoutSessionId(session.getId())
                            .ifPresent(order -> orderStatusService.markCancelled(order.getId())));
            case "payment_intent.succeeded" -> deserialize(event, PaymentIntent.class).ifPresent(intent ->
                    orderRepository.findByStripePaymentIntentId(intent.getId())
                            .filter(order -> order.getPaymentStatus() == PaymentStatus.PENDING)
                            .ifPresent(order -> orderStatusService.markPaid(order.getId(), intent.getId(),
                                    stripeAmount(intent.getAmount()))));
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

    // Per-call, not a shared static field (com.stripe.Stripe.apiKey is a JVM-global static — under
    // concurrent requests from different stores, one tenant's checkout could run under another
    // tenant's Stripe key). Every real Stripe API call in this class must pass this explicitly.
    // Package-visible for the same reason as buildSessionParams — lets tests assert the decrypted
    // API key without a real Stripe call.
    RequestOptions requestOptions() {
        return RequestOptions.builder().setApiKey(encryptedSettingValue("stripe_secret_key", fallbackSecretKey)).build();
    }

    private String settingValue(String key, String fallback) {
        return settingRepository.findByStoreIdAndSettingKey(TenantContext.requireStoreId(), key)
                .map(s -> s.getValue()).filter(v -> v != null && !v.isBlank()).orElse(fallback);
    }

    // Like settingValue(), but for secrets stored encrypted by SettingsServiceImpl.setSecret()
    // (see SecretCrypto) — decrypts the stored value. The fallback (from application.properties)
    // is used as-is, un-decrypted, since it was never encrypted in the first place.
    private String encryptedSettingValue(String key, String fallback) {
        return settingRepository.findByStoreIdAndSettingKey(TenantContext.requireStoreId(), key)
                .map(s -> s.getValue()).filter(v -> v != null && !v.isBlank())
                .map(org.uvo.uvostore.service.settings.SecretCrypto::decrypt)
                .orElse(fallback);
    }

    // Stripe reports amounts in the currency's smallest unit. CLP has none, and
    // createCheckoutSession sends order.getTotal() as-is, so for this store's currency the value
    // maps straight back. A null (Stripe reported none) is passed through so markPaid can treat
    // "unknown" as a mismatch instead of silently accepting it.
    private java.math.BigDecimal stripeAmount(Long amountInSmallestUnit) {
        return amountInSmallestUnit == null ? null : java.math.BigDecimal.valueOf(amountInSmallestUnit);
    }
}
