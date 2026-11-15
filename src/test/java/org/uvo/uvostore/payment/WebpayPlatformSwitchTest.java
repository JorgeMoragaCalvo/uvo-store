package org.uvo.uvostore.payment;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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
import org.uvo.uvostore.service.payment.WebpayPlatformPolicy;
import org.uvo.uvostore.support.IntegrationTestSupport;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * IMP-02. Con {@code WEBPAY_ENABLED=false}, ninguna tienda puede activar, ofrecer ni iniciar Webpay, tampoco con una
 * configuración guardada antes de que la plataforma lo apagara.
 *
 * <p>Que lo ya iniciado sigue funcionando (conciliar y reembolsar) lo prueban {@code WebpayReconcileTest} y
 * {@code WebpayRefundTest}, que construyen el servicio con el interruptor apagado. Y que con Webpay activado todo sigue
 * como antes, el resto de la suite, que corre en development con el valor vacío.
 */
@SpringBootTest(properties = "webpay.enabled=false")
class WebpayPlatformSwitchTest extends IntegrationTestSupport {

    @Autowired
    private PaymentGatewayConfigRepository gatewayRepository;

    @Autowired
    private OrderRepository orderRepository;

    private Store store;
    private String token;

    @BeforeEach
    void setUp() throws Exception {
        store = createStore("webpay-off");
        token = loginAdmin(store, createAdmin(store, "webpay-off"));
    }

    @Test
    @DisplayName("El panel no puede activar Webpay, y la configuración no cambia")
    void thePanelCannotEnableWebpay() throws Exception {
        mockMvc.perform(admin(put("/api/admin/payment-gateways/WEBPAY"))
                        .contentType("application/json")
                        .content("{\"enabled\":true,\"credentials\":{\"childCommerceCode\":\"597012345678\"}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(WebpayPlatformPolicy.UNAVAILABLE_REASON));

        assertThat(gatewayRepository.findByStoreIdAndGateway(store.getId(), PaymentGatewayType.WEBPAY)).isEmpty();
        assertThat(auditRows(store, "PAYMENT_GATEWAY_UPDATE")).isEmpty();
    }

    @Test
    @DisplayName("Una tienda que lo tenía activado de antes puede desactivarlo")
    void aStoreCanStillTurnItOff() throws Exception {
        enabledWebpayFromBefore();

        mockMvc.perform(admin(put("/api/admin/payment-gateways/WEBPAY"))
                        .contentType("application/json")
                        .content("{\"enabled\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false));
    }

    @Test
    @DisplayName("El panel sabe que Webpay no está disponible, y por qué; las demás pasarelas sí")
    void thePanelIsToldWhy() throws Exception {
        mockMvc.perform(admin(get("/api/admin/payment-gateways/availability")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.gateway == 'WEBPAY')].available").value(false))
                .andExpect(jsonPath("$[?(@.gateway == 'WEBPAY')].reason").value(WebpayPlatformPolicy.UNAVAILABLE_REASON))
                .andExpect(jsonPath("$[?(@.gateway == 'MERCADOPAGO')].available").value(true));
    }

    @Test
    @DisplayName("Una configuración activada de antes no se ofrece en el checkout")
    void aPreexistingConfigIsNotOffered() throws Exception {
        enabledWebpayFromBefore();

        mockMvc.perform(get("/api/v1/checkout/config").header("Host", hostHeader(store)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.webpayEnabled").value(false));
    }

    @Test
    @DisplayName("No se crea una orden con Webpay, ni con una llamada directa a la API")
    void noOrderIsCreatedWithWebpay() throws Exception {
        enabledWebpayFromBefore();
        long before = orderRepository.count();

        mockMvc.perform(post("/api/v1/checkout")
                        .header("Host", hostHeader(store))
                        .contentType("application/json")
                        .content(checkoutBody(product(), "webpay")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(WebpayPlatformPolicy.UNAVAILABLE_REASON));

        assertThat(orderRepository.count()).isEqualTo(before);
    }

    @Test
    @DisplayName("No se inicia un pago Webpay, aunque la orden y la configuración existan")
    void noPaymentIsStarted() throws Exception {
        enabledWebpayFromBefore();
        // Una orden de antes (o creada como manual): el inicio del pago tiene que rechazarla por sí mismo.
        String response = mockMvc.perform(post("/api/v1/checkout")
                        .header("Host", hostHeader(store))
                        .contentType("application/json")
                        .content(checkoutBody(product(), "manual")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        long orderId = objectMapper.readTree(response).get("orderId").asLong();

        mockMvc.perform(post("/api/v1/webpay/create")
                        .header("Host", hostHeader(store))
                        .contentType("application/json")
                        .content("{\"orderId\":" + orderId + "}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(WebpayPlatformPolicy.UNAVAILABLE_REASON));

        assertThat(orderRepository.findById(orderId).orElseThrow().getPaymentId()).isNull();
    }

    @Test
    @DisplayName("Las demás formas de pago siguen funcionando")
    void otherPaymentMethodsAreUntouched() throws Exception {
        mockMvc.perform(post("/api/v1/checkout")
                        .header("Host", hostHeader(store))
                        .contentType("application/json")
                        .content(checkoutBody(product(), "manual")))
                .andExpect(status().isOk());

        mockMvc.perform(admin(put("/api/admin/payment-gateways/MERCADOPAGO"))
                        .contentType("application/json")
                        .content("{\"enabled\":true,\"credentials\":{\"accessToken\":\"tok\",\"webhookSecret\":\"whsec\"}}"))
                .andExpect(status().isOk());
    }

    /** Lo que deja una tienda que activó Webpay antes de que la plataforma lo apagara. */
    private void enabledWebpayFromBefore() {
        PaymentGatewayConfig config = new PaymentGatewayConfig();
        config.setStore(store);
        config.setGateway(PaymentGatewayType.WEBPAY);
        config.setEnabled(true);
        config.setCredentials(new HashMap<>(Map.of("childCommerceCode", "597012345678")));
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
}
