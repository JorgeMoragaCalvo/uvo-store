package org.uvo.uvostore.admin;

import org.junit.jupiter.api.Test;
import org.uvo.uvostore.entity.security.User;
import org.uvo.uvostore.entity.tenant.Store;
import org.uvo.uvostore.support.IntegrationTestSupport;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// CRUD + cross-tenant coverage for /api/admin/shipping/{zones,methods,rates} — a rate always
// references a method and a zone, so the rate tests create both first via the same admin.
class AdminShippingCrudTest extends IntegrationTestSupport {

    @Test
    void shippingZoneCanBeCreatedUpdatedToggledAndDeleted() throws Exception {
        Store store = createStore("zone-crud");
        User admin = createAdmin(store, "zone-crud");
        String token = loginAdmin(store, admin);

        String createResponse = mockMvc.perform(post("/api/admin/shipping/zones")
                        .header("Host", hostHeader(store))
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content("{\"name\":\"Zona Central\",\"regions\":[\"RM\"],\"communes\":[],\"active\":true,\"sortOrder\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Zona Central"))
                .andReturn().getResponse().getContentAsString();
        long zoneId = objectMapper.readTree(createResponse).get("id").asLong();

        mockMvc.perform(put("/api/admin/shipping/zones/" + zoneId)
                        .header("Host", hostHeader(store))
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content("{\"name\":\"Zona Central Actualizada\",\"regions\":[\"RM\"],\"communes\":[],\"active\":true,\"sortOrder\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Zona Central Actualizada"));

        mockMvc.perform(post("/api/admin/shipping/zones/" + zoneId + "/toggle-status")
                        .header("Host", hostHeader(store))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));

        mockMvc.perform(delete("/api/admin/shipping/zones/" + zoneId)
                        .header("Host", hostHeader(store))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/admin/shipping/zones/" + zoneId)
                        .header("Host", hostHeader(store))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }

    @Test
    void shippingZoneEndpointsRejectAnotherStoresZone() throws Exception {
        Store storeA = createStore("zone-cross-a");
        Store storeB = createStore("zone-cross-b");
        User adminA = createAdmin(storeA, "zone-cross-a");
        User adminB = createAdmin(storeB, "zone-cross-b");
        String tokenA = loginAdmin(storeA, adminA);
        String tokenB = loginAdmin(storeB, adminB);

        String createResponse = mockMvc.perform(post("/api/admin/shipping/zones")
                        .header("Host", hostHeader(storeB))
                        .header("Authorization", "Bearer " + tokenB)
                        .contentType("application/json")
                        .content("{\"name\":\"Solo B\",\"regions\":[\"RM\"],\"communes\":[],\"active\":true,\"sortOrder\":0}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        long zoneIdB = objectMapper.readTree(createResponse).get("id").asLong();

        mockMvc.perform(get("/api/admin/shipping/zones/" + zoneIdB)
                        .header("Host", hostHeader(storeA))
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isNotFound());

        mockMvc.perform(delete("/api/admin/shipping/zones/" + zoneIdB)
                        .header("Host", hostHeader(storeA))
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isNotFound());
    }

    @Test
    void shippingMethodCanBeCreatedUpdatedAndDeleted() throws Exception {
        Store store = createStore("method-crud");
        User admin = createAdmin(store, "method-crud");
        String token = loginAdmin(store, admin);

        String createResponse = mockMvc.perform(post("/api/admin/shipping/methods")
                        .header("Host", hostHeader(store))
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content("{\"name\":\"Despacho estándar\",\"code\":\"standard\",\"type\":\"COURIER\",\"hasApiIntegration\":false,\"active\":true,\"sortOrder\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Despacho estándar"))
                .andReturn().getResponse().getContentAsString();
        long methodId = objectMapper.readTree(createResponse).get("id").asLong();

        mockMvc.perform(put("/api/admin/shipping/methods/" + methodId)
                        .header("Host", hostHeader(store))
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content("{\"name\":\"Despacho express\",\"code\":\"standard\",\"type\":\"COURIER\",\"hasApiIntegration\":false,\"active\":true,\"sortOrder\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Despacho express"));

        mockMvc.perform(delete("/api/admin/shipping/methods/" + methodId)
                        .header("Host", hostHeader(store))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/admin/shipping/methods/" + methodId)
                        .header("Host", hostHeader(store))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }

    @Test
    void shippingRateCanBeCreatedUpdatedAndDeleted() throws Exception {
        Store store = createStore("rate-crud");
        User admin = createAdmin(store, "rate-crud");
        String token = loginAdmin(store, admin);
        long methodId = createMethod(store, token);
        long zoneId = createZone(store, token);

        String createResponse = mockMvc.perform(post("/api/admin/shipping/rates")
                        .header("Host", hostHeader(store))
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content("{\"methodId\":" + methodId + ",\"zoneId\":" + zoneId
                                + ",\"name\":\"Tarifa base\",\"rateType\":\"flat\",\"flatRate\":3000,\"active\":true,\"sortOrder\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Tarifa base"))
                .andExpect(jsonPath("$.flatRate").value(3000))
                .andReturn().getResponse().getContentAsString();
        long rateId = objectMapper.readTree(createResponse).get("id").asLong();

        mockMvc.perform(put("/api/admin/shipping/rates/" + rateId)
                        .header("Host", hostHeader(store))
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content("{\"methodId\":" + methodId + ",\"zoneId\":" + zoneId
                                + ",\"name\":\"Tarifa base\",\"rateType\":\"flat\",\"flatRate\":4500,\"active\":true,\"sortOrder\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.flatRate").value(4500));

        mockMvc.perform(delete("/api/admin/shipping/rates/" + rateId)
                        .header("Host", hostHeader(store))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/admin/shipping/rates/" + rateId)
                        .header("Host", hostHeader(store))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }

    @Test
    void shippingRateCreationRejectsAnotherStoresMethodOrZone() throws Exception {
        Store storeA = createStore("rate-cross-a");
        Store storeB = createStore("rate-cross-b");
        User adminA = createAdmin(storeA, "rate-cross-a");
        User adminB = createAdmin(storeB, "rate-cross-b");
        String tokenA = loginAdmin(storeA, adminA);
        String tokenB = loginAdmin(storeB, adminB);

        long methodB = createMethod(storeB, tokenB);
        long zoneA = createZone(storeA, tokenA);

        mockMvc.perform(post("/api/admin/shipping/rates")
                        .header("Host", hostHeader(storeA))
                        .header("Authorization", "Bearer " + tokenA)
                        .contentType("application/json")
                        .content("{\"methodId\":" + methodB + ",\"zoneId\":" + zoneA
                                + ",\"name\":\"Cruzada\",\"rateType\":\"flat\",\"flatRate\":1000,\"active\":true,\"sortOrder\":0}"))
                .andExpect(status().isNotFound());
    }

    private long createMethod(Store store, String token) throws Exception {
        String response = mockMvc.perform(post("/api/admin/shipping/methods")
                        .header("Host", hostHeader(store))
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content("{\"name\":\"Método " + nextSeq() + "\",\"code\":\"code-" + nextSeq()
                                + "\",\"type\":\"COURIER\",\"hasApiIntegration\":false,\"active\":true,\"sortOrder\":0}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).get("id").asLong();
    }

    private long createZone(Store store, String token) throws Exception {
        String response = mockMvc.perform(post("/api/admin/shipping/zones")
                        .header("Host", hostHeader(store))
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content("{\"name\":\"Zona " + nextSeq() + "\",\"regions\":[\"RM\"],\"communes\":[],\"active\":true,\"sortOrder\":0}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).get("id").asLong();
    }
}
