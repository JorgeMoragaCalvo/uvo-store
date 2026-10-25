package org.uvo.uvostore.audit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import org.uvo.uvostore.entity.order.Order;
import org.uvo.uvostore.entity.order.enums.FulfillmentStatus;
import org.uvo.uvostore.entity.order.enums.OrderStatus;
import org.uvo.uvostore.entity.order.enums.PaymentMethodType;
import org.uvo.uvostore.entity.order.enums.PaymentStatus;
import org.uvo.uvostore.entity.security.User;
import org.uvo.uvostore.entity.tenant.Store;
import org.uvo.uvostore.repository.OrderRepository;
import org.uvo.uvostore.support.IntegrationTestSupport;
import org.uvo.uvostore.support.WithPosIntegration;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * PROD-18. "Nunca secretos ni cuerpos completos", comprobado en bloque: cada operación auditada corre con secretos
 * marcados (contraseñas, tokens, credenciales, y también texto libre que no hace falta guardar), y después ninguna fila
 * de auditoría contiene ninguno.
 *
 * <p>Para que no pase vacío, exige además que las operaciones hayan dejado sus filas: un barrido sin filas no
 * acreditaría nada.
 */
@WithPosIntegration
class AuditSecretSweepTest extends IntegrationTestSupport {

    @Value("${app.platform-api-key}")
    private String platformKey;
    @Autowired
    private OrderRepository orderRepository;

    private final List<String> markers = new ArrayList<>();
    private Store store;
    private String token;

    @Test
    @DisplayName("Ninguna fila de auditoría lleva un secreto, un token ni un texto libre de los que se mandaron")
    void noAuditRowCarriesASecret() throws Exception {
        long baseline = jdbcTemplate.queryForObject("SELECT coalesce(max(id), 0) FROM audit_events", Long.class);
        store = createStore("audit-sweep");
        User admin = createAdmin(store, "audit-sweep");
        token = loginAdmin(store, admin);

        settingsWithStripeAndPos();
        gatewaysAndCarrier();
        usersAndAuth(admin);
        String posStore = platformOnboardingWithPos();
        refundAndCoupon();
        platformRecoveryAndClosing(posStore);

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT action, result, actor_label, request_id, details::text AS details FROM audit_events "
                        + "WHERE id > ? ORDER BY id", baseline);
        for (Map<String, Object> row : rows) {
            String text = row.get("actor_label") + " " + row.get("request_id") + " " + row.get("details");
            for (String marker : markers) {
                assertThat(text).as("%s lleva un secreto", row.get("action")).doesNotContain(marker);
            }
            assertThat(String.valueOf(row.get("details"))).hasSizeLessThan(2000);
        }
        assertThat(rows).extracting(row -> (String) row.get("action")).contains(
                "SETTINGS_STRIPE_UPDATE", "POS_CONNECTION_UPDATE", "PAYMENT_GATEWAY_UPDATE",
                "SHIPPING_METHOD_CREDENTIALS_UPDATE", "USER_CREATE", "USER_UPDATE", "AUTH_LOGIN",
                "USER_INVITATION_ACCEPT", "USER_PASSWORD_RESET", "STORE_CREATE", "REFUND_RECORD_EXTERNAL",
                "COUPON_CREATE", "STORE_OWNER_ACCESS_RECOVERY", "STORE_STATUS_UPDATE", "STORE_CLOSE");
        assertThat(rows).anySatisfy(row -> {
            assertThat(row.get("action")).isEqualTo("AUTH_LOGIN");
            assertThat(row.get("result")).isEqualTo("FAILURE");
        });
    }

    private void settingsWithStripeAndPos() throws Exception {
        long company = 890_000_000L + nextSeq();
        String body = """
                {
                  "storeName":"Tienda","storeEmail":"tienda@test.local","storePhone":"","adminEmail":"admin@test.local",
                  "currency":"CLP","currencySymbol":"$","taxRate":"19","pricesIncludeTax":true,
                  "shippingEnabled":true,"defaultShippingCost":"0","freeShippingEnabled":false,"freeShippingThreshold":"0",
                  "allowGuestCheckout":true,"requirePhone":false,"requireCompany":false,
                  "stripePublicKey":"%s","stripeSecretKey":"%s","stripeEnabled":true,
                  "posApiUrl":"https://pos.test","posApiToken":"uvp_%d_%s","posWebhookSecret":"%s","posSyncEnabled":true,
                  "metaTitle":"","metaDescription":"","metaKeywords":"","facebookUrl":"","instagramUrl":"","twitterUrl":""
                }
                """.formatted(marker(), marker(), company, marker(), marker());
        mockMvc.perform(auth(put("/api/admin/settings/general")).contentType("application/json").content(body))
                .andExpect(status().isOk());
    }

    private void gatewaysAndCarrier() throws Exception {
        mockMvc.perform(auth(put("/api/admin/payment-gateways/MERCADOPAGO")).contentType("application/json")
                        .content("{\"enabled\":true,\"credentials\":{\"accessToken\":\"%s\",\"webhookSecret\":\"%s\",\"publicKey\":\"%s\"}}"
                                .formatted(marker(), marker(), marker())))
                .andExpect(status().isOk());
        mockMvc.perform(auth(put("/api/admin/payment-gateways/WEBPAY")).contentType("application/json")
                        .content("{\"enabled\":true,\"credentials\":{\"childCommerceCode\":\"%s\"}}".formatted(marker())))
                .andExpect(status().isOk());

        String method = mockMvc.perform(auth(post("/api/admin/shipping/methods")).contentType("application/json")
                        .content("{\"name\":\"Courier\",\"code\":\"sweep-" + nextSeq()
                                + "\",\"type\":\"COURIER\",\"hasApiIntegration\":false,\"active\":true,\"sortOrder\":0}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        long methodId = objectMapper.readTree(method).get("id").asLong();
        mockMvc.perform(auth(put("/api/admin/shipping/methods/" + methodId + "/credentials")).contentType("application/json")
                        .content("{\"subscriptionKey\":\"%s\"}".formatted(marker())))
                .andExpect(status().isOk());
    }

    private void usersAndAuth(User admin) throws Exception {
        // Alta con contraseña, teléfono y notas; después edición con otra contraseña y otras notas.
        String email = "sweep-" + nextSeq() + "@test.local";
        mockMvc.perform(auth(multipart("/api/admin/users"))
                        .param("name", "Vendedor")
                        .param("email", email)
                        .param("password", marker())
                        .param("phone", marker())
                        .param("notes", marker())
                        .param("active", "true")
                        .param("sendInvitation", "false"))
                .andExpect(status().isOk());
        User created = userRepository.findByStoreIdAndEmail(store.getId(), email).orElseThrow();
        MockMultipartHttpServletRequestBuilder update = multipart("/api/admin/users/" + created.getId());
        update.with(req -> {
            req.setMethod("PUT");
            return req;
        });
        mockMvc.perform(auth(update)
                        .param("name", "Vendedor")
                        .param("email", email)
                        .param("password", marker())
                        .param("phone", marker())
                        .param("notes", marker())
                        .param("active", "true")
                        .param("sendInvitation", "false"))
                .andExpect(status().isOk());

        // Un login fallido de una cuenta que existe, y uno con un correo que no existe (y que es un "secreto").
        login(admin.getEmail(), marker(), 401);
        login(marker() + "@test.local", marker(), 401);

        // Una invitación aceptada: ni el token ni la contraseña elegida.
        String invitedEmail = "sweep-invitado-" + nextSeq() + "@test.local";
        mockMvc.perform(auth(multipart("/api/admin/users"))
                        .param("name", "Invitado")
                        .param("email", invitedEmail)
                        .param("active", "true")
                        .param("sendInvitation", "true"))
                .andExpect(status().isOk());
        String invitationToken = userRepository.findByStoreIdAndEmail(store.getId(), invitedEmail).orElseThrow()
                .getInvitationToken();
        markers.add(invitationToken);
        mockMvc.perform(post("/api/admin/auth/accept-invitation")
                        .header("Host", hostHeader(store))
                        .contentType("application/json")
                        .content("{\"token\":\"%s\",\"password\":\"%s\"}".formatted(invitationToken, marker())))
                .andExpect(status().isOk());

        // Olvidé mi contraseña y restablecimiento: ni el token del enlace ni la contraseña nueva.
        mockMvc.perform(post("/api/admin/auth/forgot-password")
                        .header("Host", hostHeader(store))
                        .contentType("application/json")
                        .content("{\"email\":\"" + admin.getEmail() + "\"}"))
                .andExpect(status().isOk());
        String resetToken = userRepository.findById(admin.getId()).orElseThrow().getPasswordResetToken();
        markers.add(resetToken);
        mockMvc.perform(post("/api/admin/auth/reset-password")
                        .contentType("application/json")
                        .content("{\"token\":\"%s\",\"password\":\"%s\"}".formatted(resetToken, marker())))
                .andExpect(status().isOk());
        // El restablecimiento revocó la sesión del admin: se vuelve a entrar para lo que sigue.
        admin.setPassword(passwordEncoder.encode(TEST_PASSWORD));
        userRepository.save(admin);
        token = loginAdmin(store, admin);
    }

    /** @return el id de la tienda creada */
    private String platformOnboardingWithPos() throws Exception {
        long company = 891_000_000L + nextSeq();
        String body = """
                {"slug":"audit-sweep-pos-%d","domain":"","storeName":"Tienda del POS","adminName":"Dueno",
                 "adminEmail":"dueno-sweep-%d@test.local",
                 "pos":{"companyId":%d,"companyName":"Empresa","apiUrl":"http://pos.internal/%s",
                        "apiKey":"uvp_%d_%s","webhookSecret":"%s"}}"""
                .formatted(nextSeq(), nextSeq(), company, marker(), company, marker(), marker());
        String response = mockMvc.perform(post("/api/platform/stores")
                        .header("X-Platform-Key", platformKey)
                        .header("X-Platform-Operator", "op-sweep")
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).get("storeId").asText();
    }

    /**
     * PROD-23. La recuperación de acceso (ni el token nuevo) y el cierre, que borra la conexión con el POS cuyos secretos
     * marcó el alta: ninguno puede aparecer en la fila del cierre.
     */
    private void platformRecoveryAndClosing(String storeId) throws Exception {
        // Con otro correo: el alta acaba de invitar al dueño, y con el mismo habría que esperar.
        mockMvc.perform(post("/api/platform/stores/" + storeId + "/owner/access-recovery")
                        .header("X-Platform-Key", platformKey)
                        .contentType("application/json")
                        .content("{\"email\":\"dueno-recuperado-" + nextSeq() + "@test.local\"}"))
                .andExpect(status().isOk());
        Long ownerId = storeRepository.findById(Long.valueOf(storeId)).orElseThrow().getOwnerUserId();
        markers.add(userRepository.findById(ownerId).orElseThrow().getInvitationToken());

        mockMvc.perform(post("/api/platform/stores/" + storeId + "/suspend")
                        .header("X-Platform-Key", platformKey)
                        .contentType("application/json")
                        .content("{\"reason\":\"MODULE_DEACTIVATED\"}"))
                .andExpect(status().isOk());
        String slug = storeRepository.findById(Long.valueOf(storeId)).orElseThrow().getSlug();
        mockMvc.perform(post("/api/platform/stores/" + storeId + "/close")
                        .header("X-Platform-Key", platformKey)
                        .contentType("application/json")
                        .content("{\"confirmSlug\":\"" + slug + "\",\"reason\":\"MODULE_DEACTIVATED\"}"))
                .andExpect(status().isOk());
    }

    private void refundAndCoupon() throws Exception {
        Order order = new Order();
        order.setStore(store);
        order.setOrderNumber("ORD-SWEEP-" + nextSeq());
        order.setCustomerEmail("comprador@test.local");
        order.setCustomerFirstName("Test");
        order.setCustomerLastName("Comprador");
        order.setSubtotal(BigDecimal.valueOf(10000));
        order.setDiscountAmount(BigDecimal.ZERO);
        order.setShippingCost(BigDecimal.ZERO);
        order.setTaxAmount(BigDecimal.ZERO);
        order.setTotal(BigDecimal.valueOf(10000));
        order.setStatus(OrderStatus.PROCESSING);
        order.setPaymentStatus(PaymentStatus.PAID);
        order.setPaymentMethod(PaymentMethodType.MANUAL);
        order.setFulfillmentStatus(FulfillmentStatus.UNFULFILLED);
        orderRepository.save(order);
        mockMvc.perform(auth(post("/api/admin/orders/" + order.getId() + "/refund/external"))
                        .contentType("application/json")
                        .content("{\"amount\":1000,\"reason\":\"%s\"}".formatted(marker())))
                .andExpect(status().isOk());

        mockMvc.perform(auth(post("/api/admin/coupons")).contentType("application/json")
                        .content("{\"code\":\"SWEEP%d\",\"name\":\"Cupon\",\"description\":\"%s\",\"type\":\"fixed\",\"value\":500,\"active\":true}"
                                .formatted(nextSeq(), marker())))
                .andExpect(status().isOk());
    }

    private void login(String email, String password, int expected) throws Exception {
        mockMvc.perform(post("/api/admin/auth/login")
                        .header("Host", hostHeader(store))
                        .contentType("application/json")
                        .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, password)))
                .andExpect(status().is(expected));
    }

    /** Un secreto nuevo y reconocible (33 caracteres: cumple los mínimos de largo del POS). */
    private String marker() {
        String marker = "S" + UUID.randomUUID().toString().replace("-", "");
        markers.add(marker);
        return marker;
    }

    private <T extends AbstractMockHttpServletRequestBuilder<?>> T auth(T request) {
        request.header("Host", hostHeader(store)).header("Authorization", "Bearer " + token);
        return request;
    }
}
