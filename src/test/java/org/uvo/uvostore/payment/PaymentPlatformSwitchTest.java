package org.uvo.uvostore.payment;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.uvo.uvostore.entity.catalog.Category;
import org.uvo.uvostore.entity.catalog.Product;
import org.uvo.uvostore.entity.payment.PaymentGatewayConfig;
import org.uvo.uvostore.entity.payment.enums.PaymentGatewayType;
import org.uvo.uvostore.entity.tenant.Store;
import org.uvo.uvostore.repository.OrderRepository;
import org.uvo.uvostore.repository.PaymentGatewayConfigRepository;
import org.uvo.uvostore.service.payment.PaymentPlatformPolicy;
import org.uvo.uvostore.support.IntegrationTestSupport;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * IMP-02 / IMP-07. Con {@code WEBPAY_ENABLED}, {@code STRIPE_ENABLED} y {@code MERCADOPAGO_ENABLED} en {@code false},
 * ninguna tienda puede activar, ofrecer ni iniciar esas pasarelas, tampoco con una configuración guardada antes de que
 * la plataforma las apagara.
 *
 * <p>Que lo ya iniciado sigue funcionando (conciliar y reembolsar) lo prueban {@code WebpayReconcileTest},
 * {@code WebpayRefundTest} y {@code MercadoPagoRefundTest}, que construyen el servicio con el interruptor apagado. Y
 * que con las pasarelas activadas todo sigue como antes, el resto de la suite, que corre en development con los valores
 * vacíos.
 */
@SpringBootTest(properties = {"webpay.enabled=false", "stripe.enabled=false", "mercadopago.enabled=false"})
class PaymentPlatformSwitchTest extends IntegrationTestSupport {

    private static final Map<PaymentGatewayType, String> START_PAYMENT = Map.of(
            PaymentGatewayType.WEBPAY, "/api/v1/webpay/create",
            PaymentGatewayType.STRIPE, "/api/v1/create-checkout-session",
            PaymentGatewayType.MERCADOPAGO, "/api/v1/mercadopago/create-preference");

    @Autowired
    private PaymentGatewayConfigRepository gatewayRepository;

    @Autowired
    private OrderRepository orderRepository;

    private Store store;
    private String token;

    @BeforeEach
    void setUp() throws Exception {
        store = createStore("gateways-off");
        token = loginAdmin(store, createAdmin(store, "gateways-off"));
    }

    @Test
    @DisplayName("El panel no puede activar Webpay ni MercadoPago, y la configuración no cambia")
    void thePanelCannotEnableAGateway() throws Exception {
        mockMvc.perform(admin(put("/api/admin/payment-gateways/WEBPAY"))
                        .contentType("application/json")
                        .content("{\"enabled\":true,\"credentials\":{\"childCommerceCode\":\"597012345678\"}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(PaymentPlatformPolicy.unavailableReason(PaymentGatewayType.WEBPAY)));
        mockMvc.perform(admin(put("/api/admin/payment-gateways/MERCADOPAGO"))
                        .contentType("application/json")
                        .content("{\"enabled\":true,\"credentials\":{\"accessToken\":\"tok\",\"webhookSecret\":\"whsec\"}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        PaymentPlatformPolicy.unavailableReason(PaymentGatewayType.MERCADOPAGO)));

        assertThat(gatewayRepository.findByStoreId(store.getId())).isEmpty();
        assertThat(auditRows(store, "PAYMENT_GATEWAY_UPDATE")).isEmpty();
    }

    @Test
    @DisplayName("Stripe tampoco se puede activar desde los ajustes generales")
    void theSettingsCannotEnableStripe() throws Exception {
        mockMvc.perform(admin(put("/api/admin/settings/general"))
                        .contentType("application/json")
                        .content(generalSettings(true)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(PaymentPlatformPolicy.unavailableReason(PaymentGatewayType.STRIPE)));

        // Apagado, el formulario se sigue guardando: la tienda no queda sin poder cambiar el resto.
        mockMvc.perform(admin(put("/api/admin/settings/general"))
                        .contentType("application/json")
                        .content(generalSettings(false)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Una tienda que tenía una pasarela activada de antes puede desactivarla")
    void aStoreCanStillTurnItOff() throws Exception {
        enabledFromBefore(PaymentGatewayType.MERCADOPAGO);

        mockMvc.perform(admin(put("/api/admin/payment-gateways/MERCADOPAGO"))
                        .contentType("application/json")
                        .content("{\"enabled\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false));
    }

    @Test
    @DisplayName("El panel sabe qué pasarelas no están disponibles, y por qué")
    void thePanelIsToldWhy() throws Exception {
        var request = mockMvc.perform(admin(get("/api/admin/payment-gateways/availability")))
                .andExpect(status().isOk());
        for (PaymentGatewayType gateway : PaymentGatewayType.values()) {
            String path = "$[?(@.gateway == '" + gateway.name() + "')]";
            request.andExpect(jsonPath(path + ".available").value(false))
                    .andExpect(jsonPath(path + ".reason").value(PaymentPlatformPolicy.unavailableReason(gateway)));
        }
    }

    @Test
    @DisplayName("Una configuración activada de antes no se ofrece en el checkout")
    void aPreexistingConfigIsNotOffered() throws Exception {
        enabledFromBefore(PaymentGatewayType.WEBPAY);
        enabledFromBefore(PaymentGatewayType.MERCADOPAGO);
        setSetting(store, "stripe_enabled", "true");

        mockMvc.perform(get("/api/v1/checkout/config").header("Host", hostHeader(store)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.webpayEnabled").value(false))
                .andExpect(jsonPath("$.mercadopagoEnabled").value(false))
                .andExpect(jsonPath("$.stripeEnabled").value(false));
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(PaymentGatewayType.class)
    @DisplayName("No se crea una orden con una pasarela apagada, ni con una llamada directa a la API")
    void noOrderIsCreatedWithAGatewayThatIsOff(PaymentGatewayType gateway) throws Exception {
        enabledFromBefore(gateway);
        long before = orderRepository.count();

        mockMvc.perform(post("/api/v1/checkout")
                        .header("Host", hostHeader(store))
                        .contentType("application/json")
                        .content(checkoutBody(product(), gateway.name().toLowerCase(Locale.ROOT))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(PaymentPlatformPolicy.unavailableReason(gateway)));

        assertThat(orderRepository.count()).isEqualTo(before);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(PaymentGatewayType.class)
    @DisplayName("No se inicia un pago con una pasarela apagada, aunque la orden y la configuración existan")
    void noPaymentIsStarted(PaymentGatewayType gateway) throws Exception {
        enabledFromBefore(gateway);
        // Una orden de antes (aquí, creada como manual): el inicio del pago tiene que rechazarla por sí mismo.
        String response = mockMvc.perform(post("/api/v1/checkout")
                        .header("Host", hostHeader(store))
                        .contentType("application/json")
                        .content(checkoutBody(product(), "manual")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        long orderId = objectMapper.readTree(response).get("orderId").asLong();

        mockMvc.perform(post(START_PAYMENT.get(gateway))
                        .header("Host", hostHeader(store))
                        .contentType("application/json")
                        .content("{\"orderId\":" + orderId + "}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(PaymentPlatformPolicy.unavailableReason(gateway)));

        var order = orderRepository.findById(orderId).orElseThrow();
        assertThat(order.getPaymentId()).isNull();
        assertThat(order.getStripeCheckoutSessionId()).isNull();
    }

    @Test
    @DisplayName("El pago manual sigue funcionando")
    void theManualPaymentIsUntouched() throws Exception {
        mockMvc.perform(post("/api/v1/checkout")
                        .header("Host", hostHeader(store))
                        .contentType("application/json")
                        .content(checkoutBody(product(), "manual")))
                .andExpect(status().isOk());
    }

    /** Lo que deja una tienda que activó una pasarela antes de que la plataforma la apagara. */
    private void enabledFromBefore(PaymentGatewayType gateway) {
        if (gateway == PaymentGatewayType.STRIPE) {
            setSetting(store, "stripe_enabled", "true");
            return;
        }
        PaymentGatewayConfig config = new PaymentGatewayConfig();
        config.setStore(store);
        config.setGateway(gateway);
        config.setEnabled(true);
        config.setCredentials(new HashMap<>(gateway == PaymentGatewayType.WEBPAY
                ? Map.of("childCommerceCode", "597012345678")
                : Map.of("accessToken", "tok", "webhookSecret", "whsec")));
        gatewayRepository.save(config);
    }

    private Product product() {
        disableShipping(store);
        Category category = createCategory(store, "Cat");
        return createProduct(store, category, "Producto", BigDecimal.valueOf(1000));
    }

    private MockHttpServletRequestBuilder admin(MockHttpServletRequestBuilder request) {
        return request.header("Host", hostHeader(store)).header("Authorization", "Bearer " + token);
    }

    private static String checkoutBody(Product product, String paymentMethod) {
        return """
                {
                  "customer": {"email":"a@test.local","firstName":"A","lastName":"B","phone":"+56911111111"},
                  "shippingAddress": {"addressLine1":"Calle 1","city":"Santiago","state":"RM","postalCode":"8320000","country":"CL"},
                  "region": "RM",
                  "commune": "Santiago",
                  "items": [{"id":%d,"type":"product","quantity":1}],
                  "paymentMethod": "%s"
                }
                """.formatted(product.getId(), paymentMethod);
    }

    private static String generalSettings(boolean stripeEnabled) {
        return """
                {
                  "storeName":"Tienda","storeEmail":"a@test.local","storePhone":"","adminEmail":"admin@test.local",
                  "currency":"CLP","currencySymbol":"$","taxRate":"19","pricesIncludeTax":false,
                  "shippingEnabled":true,"defaultShippingCost":"0","freeShippingEnabled":false,
                  "freeShippingThreshold":"0",
                  "allowGuestCheckout":true,"requirePhone":false,"requireCompany":false,
                  "stripePublicKey":"pk_test_1","stripeSecretKey":"sk_test_1","stripeEnabled":%s,
                  "posApiUrl":"","posApiToken":"","posWebhookSecret":"","posSyncEnabled":false,
                  "metaTitle":"","metaDescription":"","metaKeywords":"",
                  "facebookUrl":"","instagramUrl":"","twitterUrl":""
                }
                """.formatted(stripeEnabled);
    }
}
