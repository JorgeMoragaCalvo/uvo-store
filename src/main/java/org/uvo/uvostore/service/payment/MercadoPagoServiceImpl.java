package org.uvo.uvostore.service.payment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mercadopago.client.payment.PaymentClient;
import com.mercadopago.client.preference.PreferenceBackUrlsRequest;
import com.mercadopago.client.preference.PreferenceClient;
import com.mercadopago.client.preference.PreferenceItemRequest;
import com.mercadopago.client.preference.PreferenceRequest;
import com.mercadopago.core.MPRequestOptions;
import com.mercadopago.resources.payment.Payment;
import com.mercadopago.resources.preference.Preference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.uvo.uvostore.entity.order.Order;
import org.uvo.uvostore.entity.order.OrderItem;
import org.uvo.uvostore.entity.order.enums.PaymentStatus;
import org.uvo.uvostore.entity.payment.PaymentGatewayConfig;
import org.uvo.uvostore.entity.payment.enums.PaymentGatewayType;
import org.uvo.uvostore.repository.OrderRepository;
import org.uvo.uvostore.repository.PaymentGatewayConfigRepository;
import org.uvo.uvostore.security.TenantContext;
import org.uvo.uvostore.service.order.OrderStatusService;

import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;

// Checkout Pro: create a Preference (a shopping-cart-like resource), redirect the buyer to its
// initPoint, then react to an async webhook notification once they pay.
//
// SECURITY NOTE (untested — no live MercadoPago account to validate against): unlike Stripe's
// Webhook.constructEvent and Webpay's request-signing, this implementation does NOT verify a
// webhook signature — MercadoPago's signature scheme needs a merchant-specific webhook secret
// from their dashboard that we don't have yet. As a partial mitigation, the webhook handler never
// trusts the notification payload's own claims about payment status: it only reads the payment id
// out of the payload, then re-fetches that payment from MercadoPago's API using our own access
// token, and acts on THAT authenticated response. Add proper x-signature verification (see
// MercadoPago's "Webhooks" docs) once real credentials are available to test against.
@Service
public class MercadoPagoServiceImpl implements MercadoPagoService {

    private static final Logger log = LoggerFactory.getLogger(MercadoPagoServiceImpl.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final MercadoPagoWebhookSignature webhookSignature;
    private final OrderRepository orderRepository;
    private final PaymentGatewayConfigRepository configRepository;
    private final OrderStatusService orderStatusService;
    private final String frontendUrl;

    public MercadoPagoServiceImpl(
            OrderRepository orderRepository,
            PaymentGatewayConfigRepository configRepository,
            OrderStatusService orderStatusService,
            MercadoPagoWebhookSignature webhookSignature,
            @Value("${app.frontend-url}") String frontendUrl) {
        this.orderRepository = orderRepository;
        this.configRepository = configRepository;
        this.orderStatusService = orderStatusService;
        this.webhookSignature = webhookSignature;
        this.frontendUrl = frontendUrl;
    }

    @Override
    @Transactional
    public MercadoPagoPreferenceResult createPreference(
            Long orderId, String successUrl, String failureUrl, String pendingUrl, String notificationUrl) {
        Long storeId = TenantContext.requireStoreId();
        Order order = orderRepository.findById(orderId)
                .filter(o -> o.getStore().getId().equals(storeId))
                .orElseThrow(() -> new NoSuchElementException("Order " + orderId + " not found"));

        if (order.getPaymentStatus() != PaymentStatus.PENDING) {
            throw new IllegalStateException("Esta orden ya fue procesada");
        }

        String accessToken = requireAccessToken(storeId);

        List<PreferenceItemRequest> items = new ArrayList<>();
        for (OrderItem item : order.getItems()) {
            items.add(PreferenceItemRequest.builder()
                    .title(item.getProductName())
                    .quantity(item.getQuantity())
                    .unitPrice(item.getPrice().setScale(0, RoundingMode.HALF_UP))
                    .currencyId("CLP")
                    .build());
        }
        if (order.getShippingCost() != null && order.getShippingCost().signum() > 0) {
            items.add(lineItem("Envío", order.getShippingCost()));
        }
        if (order.getTaxAmount() != null && order.getTaxAmount().signum() > 0) {
            items.add(lineItem("IVA", order.getTaxAmount()));
        }

        PreferenceRequest request = PreferenceRequest.builder()
                .items(items)
                .externalReference(order.getOrderNumber())
                .notificationUrl(notificationUrl)
                .backUrls(PreferenceBackUrlsRequest.builder()
                        .success(successUrl != null ? successUrl : frontendUrl + "/order-success?order=" + order.getOrderNumber())
                        .failure(failureUrl != null ? failureUrl : frontendUrl + "/checkout?error=mercadopago")
                        .pending(pendingUrl != null ? pendingUrl : frontendUrl + "/checkout?pending=1")
                        .build())
                .autoReturn("approved")
                .build();

        Preference preference;
        try {
            preference = new PreferenceClient().create(request, MPRequestOptions.builder().accessToken(accessToken).build());
        } catch (com.mercadopago.exceptions.MPApiException e) {
            String detail = e.getApiResponse() != null ? e.getApiResponse().getContent() : e.getMessage();
            throw new IllegalStateException("Error al crear preferencia MercadoPago: " + detail, e);
        } catch (Exception e) {
            throw new IllegalStateException("Error al crear preferencia MercadoPago: " + e.getMessage(), e);
        }

        order.setPaymentId(preference.getId());
        orderRepository.save(order);

        return new MercadoPagoPreferenceResult(preference.getId(), preference.getInitPoint());
    }

    @Override
    @Transactional
    public void handleWebhook(String payload, String signatureHeader, String requestId) {
        Long paymentId = extractPaymentId(payload);
        if (paymentId == null) {
            return; // not a payment notification (e.g. merchant_order) — nothing to do
        }

        Long storeId = TenantContext.requireStoreId();
        // M3: verified BEFORE the outbound PaymentClient.get() below — rejecting afterwards would
        // still let anyone burn the merchant's API quota, which is the whole point of the finding.
        //
        // A store with no secret configured answers 401 too, not "MercadoPago isn't enabled here":
        // an unauthenticated caller has no business learning how a store is configured, and the
        // answer is the same either way — this notification isn't going to be processed.
        String webhookSecret = configuredWebhookSecret(storeId);
        if (!webhookSignature.isValid(signatureHeader, requestId, String.valueOf(paymentId), webhookSecret)) {
            throw new BadCredentialsException("Firma de webhook de MercadoPago inválida");
        }

        String accessToken = requireAccessToken(storeId);

        Payment payment;
        try {
            payment = new PaymentClient().get(paymentId, MPRequestOptions.builder().accessToken(accessToken).build());
        } catch (Exception e) {
            log.warn("Error consultando pago MercadoPago id={}: {}", paymentId, e.getMessage());
            return;
        }

        if (payment.getExternalReference() == null) {
            return;
        }

        Order order = orderRepository.findByOrderNumber(payment.getExternalReference())
                .filter(o -> o.getStore().getId().equals(storeId))
                .orElse(null);
        if (order == null || order.getPaymentStatus() != PaymentStatus.PENDING) {
            return;
        }

        switch (payment.getStatus()) {
            // M4: transactionAmount is what the buyer was actually charged.
            case "approved" -> orderStatusService.markPaid(order.getId(), String.valueOf(payment.getId()),
                    payment.getTransactionAmount());
            case "rejected", "cancelled" -> orderStatusService.markPaymentFailed(order.getId());
            default -> {
                // "pending"/"in_process"/etc. — no state change, wait for the next notification.
            }
        }
    }

    private PreferenceItemRequest lineItem(String title, java.math.BigDecimal amount) {
        return PreferenceItemRequest.builder()
                .title(title)
                .quantity(1)
                .unitPrice(amount.setScale(0, RoundingMode.HALF_UP))
                .currencyId("CLP")
                .build();
    }

    // Null when the gateway isn't enabled or has no secret — the caller turns that into the same
    // 401 an invalid signature gets. AdminPaymentGatewayServiceImpl is what guarantees an enabled
    // MercadoPago always has one.
    private String configuredWebhookSecret(Long storeId) {
        return configRepository.findByStoreIdAndGateway(storeId, PaymentGatewayType.MERCADOPAGO)
                .filter(PaymentGatewayConfig::isEnabled)
                .map(config -> config.getCredentials().get("webhookSecret"))
                .orElse(null);
    }

    private String requireAccessToken(Long storeId) {
        PaymentGatewayConfig config = configRepository.findByStoreIdAndGateway(storeId, PaymentGatewayType.MERCADOPAGO)
                .filter(PaymentGatewayConfig::isEnabled)
                .orElseThrow(() -> new IllegalStateException("MercadoPago no está habilitado para esta tienda"));
        String accessToken = config.getCredentials().get("accessToken");
        if (accessToken == null || accessToken.isBlank()) {
            throw new IllegalStateException("Falta configurar el accessToken de MercadoPago para esta tienda");
        }
        return accessToken;
    }

    private Long extractPaymentId(String payload) {
        try {
            JsonNode root = MAPPER.readTree(payload);
            String type = root.path("type").asText(null);
            if (type != null && !"payment".equals(type)) {
                return null;
            }
            JsonNode idNode = root.path("data").path("id");
            return idNode.isMissingNode() ? null : idNode.asLong();
        } catch (Exception e) {
            return null;
        }
    }
}
