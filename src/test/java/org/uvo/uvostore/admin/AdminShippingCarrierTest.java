package org.uvo.uvostore.admin;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.uvo.uvostore.entity.security.User;
import org.uvo.uvostore.entity.tenant.Store;
import org.uvo.uvostore.support.IntegrationTestSupport;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Fase 3: a shipping method can declare a quote-only carrier (Chilexpress/Correos de Chile) and
// have its API credentials stored separately from the general edit form, encrypted at rest via the
// same converter payment_gateway_configs uses — same redaction contract as
// AdminPaymentGatewayController: never echo the raw secret, never leak it in the DB column.
class AdminShippingCarrierTest extends IntegrationTestSupport {

    @PersistenceContext
    private EntityManager entityManager;

    @Test
    void shippingMethodCanBeCreatedWithACarrier() throws Exception {
        Store store = createStore("carrier-create");
        User admin = createAdmin(store, "carrier-create");
        String token = loginAdmin(store, admin);

        mockMvc.perform(post("/api/admin/shipping/methods")
                        .header("Host", hostHeader(store))
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content("{\"name\":\"Chilexpress\",\"code\":\"chilexpress\",\"type\":\"COURIER\","
                                + "\"hasApiIntegration\":true,\"carrier\":\"CHILEXPRESS\",\"active\":true,\"sortOrder\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.carrier").value("CHILEXPRESS"))
                .andExpect(jsonPath("$.hasApiIntegration").value(true));
    }

    @Test
    void credentialsCanBeSetAndAreNeverEchoedOrStoredAsPlaintext() throws Exception {
        Store store = createStore("carrier-creds");
        User admin = createAdmin(store, "carrier-creds");
        String token = loginAdmin(store, admin);

        String createResponse = mockMvc.perform(post("/api/admin/shipping/methods")
                        .header("Host", hostHeader(store))
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content("{\"name\":\"Chilexpress\",\"code\":\"chilexpress\",\"type\":\"COURIER\","
                                + "\"hasApiIntegration\":true,\"carrier\":\"CHILEXPRESS\",\"active\":true,\"sortOrder\":0}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        long methodId = objectMapper.readTree(createResponse).get("id").asLong();

        String secret = "subscription-key-plaintext-should-never-appear-98765";
        String response = mockMvc.perform(put("/api/admin/shipping/methods/" + methodId + "/credentials")
                        .header("Host", hostHeader(store))
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content("{\"subscriptionKey\":\"" + secret + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.credentialsSet.subscriptionKey").value(true))
                .andReturn().getResponse().getContentAsString();
        assertFalse(response.contains(secret), "response must never echo the raw secret back: " + response);

        entityManager.flush();
        String rawCredentials = (String) entityManager.createNativeQuery(
                        "SELECT api_credentials FROM shipping_methods WHERE id = :id")
                .setParameter("id", methodId)
                .getSingleResult();
        assertFalse(rawCredentials.contains(secret), "api_credentials column must not contain the plaintext secret: " + rawCredentials);
    }

    @Test
    void credentialsEndpointRejectsAnotherStoresMethod() throws Exception {
        Store storeA = createStore("carrier-cross-a");
        Store storeB = createStore("carrier-cross-b");
        User adminA = createAdmin(storeA, "carrier-cross-a");
        User adminB = createAdmin(storeB, "carrier-cross-b");
        String tokenB = loginAdmin(storeB, adminB);

        String createResponse = mockMvc.perform(post("/api/admin/shipping/methods")
                        .header("Host", hostHeader(storeB))
                        .header("Authorization", "Bearer " + tokenB)
                        .contentType("application/json")
                        .content("{\"name\":\"Solo B\",\"code\":\"solo-b\",\"type\":\"COURIER\","
                                + "\"hasApiIntegration\":true,\"carrier\":\"CHILEXPRESS\",\"active\":true,\"sortOrder\":0}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        long methodIdB = objectMapper.readTree(createResponse).get("id").asLong();

        String tokenA = loginAdmin(storeA, adminA);
        mockMvc.perform(put("/api/admin/shipping/methods/" + methodIdB + "/credentials")
                        .header("Host", hostHeader(storeA))
                        .header("Authorization", "Bearer " + tokenA)
                        .contentType("application/json")
                        .content("{\"subscriptionKey\":\"stolen\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void cartCalculateNeverFailsWhenAnApiIntegratedMethodHasNoCredentialsConfigured() throws Exception {
        Store store = createStore("carrier-degrade");
        User admin = createAdmin(store, "carrier-degrade");
        String token = loginAdmin(store, admin);

        mockMvc.perform(post("/api/admin/shipping/methods")
                        .header("Host", hostHeader(store))
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content("{\"name\":\"Chilexpress\",\"code\":\"chilexpress\",\"type\":\"COURIER\","
                                + "\"hasApiIntegration\":true,\"carrier\":\"CHILEXPRESS\",\"active\":true,\"sortOrder\":0}"))
                .andExpect(status().isOk());

        var category = createCategory(store, "Cat");
        var product = createProduct(store, category, "Producto", BigDecimal.valueOf(1000));

        mockMvc.perform(post("/api/v1/cart/calculate")
                        .header("Host", hostHeader(store))
                        .contentType("application/json")
                        .content("{\"items\":[{\"id\":" + product.getId() + ",\"type\":\"product\",\"quantity\":1}],"
                                + "\"region\":\"RM\",\"commune\":\"Santiago\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.shippingCost").value(0));
    }
}
