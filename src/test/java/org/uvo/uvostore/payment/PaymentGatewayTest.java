package org.uvo.uvostore.payment;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.uvo.uvostore.entity.catalog.Category;
import org.uvo.uvostore.entity.catalog.Product;
import org.uvo.uvostore.entity.security.User;
import org.uvo.uvostore.entity.tenant.Store;
import org.uvo.uvostore.support.IntegrationTestSupport;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Validation-boundary coverage for Webpay/MercadoPago/admin gateway config — deliberately does NOT
// call the real Transbank/MercadoPago APIs (no live sandbox credentials in CI). What's asserted
// instead: a store can't create a transaction against a gateway it hasn't configured, can't touch
// another store's order, and the admin config endpoint never echoes decrypted secrets back over
// the wire or stores them as plaintext in the database.
class PaymentGatewayTest extends IntegrationTestSupport {

    @PersistenceContext
    private EntityManager entityManager;

    @Test
    void webpayCreateFailsWhenGatewayNotConfiguredForStore() throws Exception {
        Store store = createStore("webpay-noconf");
        long orderId = createPendingOrder(store);

        String body = "{\"orderId\":" + orderId + "}";

        mockMvc.perform(post("/api/v1/webpay/create")
                        .header("Host", hostHeader(store))
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void webpayCreateRejectsOrderBelongingToAnotherStore() throws Exception {
        Store storeA = createStore("webpay-cross-a");
        Store storeB = createStore("webpay-cross-b");
        long orderIdB = createPendingOrder(storeB);

        String body = "{\"orderId\":" + orderIdB + "}";

        mockMvc.perform(post("/api/v1/webpay/create")
                        .header("Host", hostHeader(storeA))
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isNotFound());
    }

    @Test
    void mercadoPagoCreatePreferenceFailsWhenGatewayNotConfiguredForStore() throws Exception {
        Store store = createStore("mp-noconf");
        long orderId = createPendingOrder(store);

        String body = "{\"orderId\":" + orderId + "}";

        mockMvc.perform(post("/api/v1/mercadopago/create-preference")
                        .header("Host", hostHeader(store))
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void adminGatewayUpdateNeverEchoesRawCredentialsInTheResponse() throws Exception {
        Store store = createStore("admin-gw-echo");
        var admin = createAdmin(store, "admin-gw-echo");
        String token = loginAdmin(store, admin);

        String secret = "super-secret-child-commerce-code-597999999999";
        String body = "{\"enabled\":true,\"credentials\":{\"childCommerceCode\":\"" + secret + "\"}}";

        String response = mockMvc.perform(put("/api/admin/payment-gateways/WEBPAY")
                        .header("Host", hostHeader(store))
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.credentialsSet.childCommerceCode").value(true))
                .andReturn().getResponse().getContentAsString();

        assertFalse(response.contains(secret), "response must never echo the raw secret back: " + response);
    }

    @Test
    void adminGatewayCredentialsAreEncryptedAtRestNotPlaintext() throws Exception {
        Store store = createStore("admin-gw-encrypt");
        var admin = createAdmin(store, "admin-gw-encrypt");
        String token = loginAdmin(store, admin);

        String secret = "accessToken-plaintext-should-never-appear-in-db-12345";
        String body = "{\"enabled\":true,\"credentials\":{\"accessToken\":\"" + secret + "\"}}";

        mockMvc.perform(put("/api/admin/payment-gateways/MERCADOPAGO")
                        .header("Host", hostHeader(store))
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isOk());

        entityManager.flush();
        String rawCredentials = (String) entityManager.createNativeQuery(
                        "SELECT credentials FROM payment_gateway_configs WHERE store_id = :storeId AND gateway = 'MERCADOPAGO'")
                .setParameter("storeId", store.getId())
                .getSingleResult();

        assertFalse(rawCredentials.contains(secret), "credentials column must not contain the plaintext secret: " + rawCredentials);
    }

    @Test
    void checkoutConfigReflectsWhetherWebpayAndMercadoPagoAreEnabledForTheStore() throws Exception {
        Store store = createStore("checkout-config-flags");
        User admin = createAdmin(store, "checkout-config-flags");
        String token = loginAdmin(store, admin);

        mockMvc.perform(get("/api/v1/checkout/config").header("Host", hostHeader(store)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.webpayEnabled").value(false))
                .andExpect(jsonPath("$.mercadopagoEnabled").value(false));

        mockMvc.perform(put("/api/admin/payment-gateways/WEBPAY")
                        .header("Host", hostHeader(store))
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content("{\"enabled\":true,\"credentials\":{\"childCommerceCode\":\"597012345678\"}}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/checkout/config").header("Host", hostHeader(store)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.webpayEnabled").value(true))
                .andExpect(jsonPath("$.mercadopagoEnabled").value(false));
    }

    @Test
    void adminGatewayListNeverLeaksAnotherStoresConfig() throws Exception {
        Store storeA = createStore("admin-gw-list-a");
        Store storeB = createStore("admin-gw-list-b");
        var adminA = createAdmin(storeA, "admin-gw-list-a");
        var adminB = createAdmin(storeB, "admin-gw-list-b");
        String tokenB = loginAdmin(storeB, adminB);

        mockMvc.perform(put("/api/admin/payment-gateways/STRIPE")
                        .header("Host", hostHeader(storeB))
                        .header("Authorization", "Bearer " + tokenB)
                        .contentType("application/json")
                        .content("{\"enabled\":true,\"credentials\":{\"secretKey\":\"sk_test_storeB\"}}"))
                .andExpect(status().isOk());

        String tokenA = loginAdmin(storeA, adminA);
        mockMvc.perform(get("/api/admin/payment-gateways")
                        .header("Host", hostHeader(storeA))
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());
    }

    private long createPendingOrder(Store store) throws Exception {
        // Gateway behaviour, not delivery — see the note in AdminCouponOrderCrudTest.
        disableShipping(store);
        Category category = createCategory(store, "Cat");
        Product product = createProduct(store, category, "Producto", BigDecimal.valueOf(1000));

        String body = """
                {
                  "customer": {"email":"a@test.local","firstName":"A","lastName":"B","phone":"+56911111111"},
                  "shippingAddress": {"addressLine1":"Calle 1","city":"Santiago","state":"RM","postalCode":"8320000","country":"CL"},
                  "region": "RM",
                  "commune": "Santiago",
                  "items": [{"id":%d,"type":"product","quantity":1}],
                  "paymentMethod": "manual"
                }
                """.formatted(product.getId());

        String response = mockMvc.perform(post("/api/v1/checkout")
                        .header("Host", hostHeader(store))
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        return objectMapper.readTree(response).get("orderId").asLong();
    }
}
