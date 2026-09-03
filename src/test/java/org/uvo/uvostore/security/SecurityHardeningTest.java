package org.uvo.uvostore.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.uvo.uvostore.entity.security.User;
import org.uvo.uvostore.entity.tenant.Store;
import org.uvo.uvostore.support.IntegrationTestSupport;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// A2, A3, A5 and A8 end to end through the real filter chain. A4 lives in RateLimitTest, which
// needs its own tiny limits and therefore its own context.
class SecurityHardeningTest extends IntegrationTestSupport {

    // --- A2: CORS allowlist -------------------------------------------------------------------

    @Test
    @DisplayName("Un origen que resuelve a una tienda real recibe permiso CORS para ese origen exacto")
    void corsAllowsAnOriginThatResolvesToAStore() throws Exception {
        Store store = createStore("cors-ok");
        // Distinct port on purpose: an Origin identical to the request's own host is same-origin,
        // and CORS never comes into play at all. This is also the real case — the SPA served by
        // Vite on :5173 against the API on :8080.
        String origin = "http://" + store.getSlug() + ".localhost:5173";

        mockMvc.perform(options("/api/v1/products")
                        .header("Host", hostHeader(store))
                        .header("Origin", origin)
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(header().string("Access-Control-Allow-Origin", origin));
    }

    @Test
    @DisplayName("Un origen desconocido no recibe cabecera de permiso: se acabó el comodín")
    void corsRejectsAnUnknownOrigin() throws Exception {
        Store store = createStore("cors-no");

        mockMvc.perform(options("/api/v1/products")
                        .header("Host", hostHeader(store))
                        .header("Origin", "http://evil.example:5173")
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }

    // --- A3: health check ---------------------------------------------------------------------

    @Test
    @DisplayName("/actuator/health responde sin autenticación, que es lo que sondea un orquestador")
    void healthIsPubliclyReachable() throws Exception {
        mockMvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }

    @Test
    @DisplayName("Abrir el health no abre el resto de actuator")
    void otherActuatorEndpointsStayClosed() throws Exception {
        mockMvc.perform(get("/actuator/env")).andExpect(status().is4xxClientError());
    }

    // --- A5: revocación de JWT ------------------------------------------------------------------

    @Test
    @DisplayName("Desactivar a un admin invalida el token que ya tenía en la mano")
    void deactivatingAnAdminRevokesItsExistingToken() throws Exception {
        Store store = createStore("revoke");
        // Two admins: AdminUserController.guardAgainstSelf forbids deactivating yourself, so the
        // deactivation has to come from someone else — which is the realistic case anyway.
        User operator = createAdmin(store, "revoke-operator");
        User victim = createAdmin(store, "revoke-victim");
        String victimToken = loginAdmin(store, victim);
        String operatorToken = loginAdmin(store, operator);

        mockMvc.perform(get("/api/admin/products")
                        .header("Host", hostHeader(store))
                        .header("Authorization", "Bearer " + victimToken))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/admin/users/" + victim.getId() + "/toggle-status")
                        .header("Host", hostHeader(store))
                        .header("Authorization", "Bearer " + operatorToken))
                .andExpect(status().isOk());

        // Same token, one request later: before A5 this kept working for the rest of the 24h.
        // 403 rather than 401 because the chain has no AuthenticationEntryPoint — an unauthenticated
        // request to a protected route is what it is; the point here is that it stopped working.
        mockMvc.perform(get("/api/admin/products")
                        .header("Host", hostHeader(store))
                        .header("Authorization", "Bearer " + victimToken))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Un token de un admin borrado deja de servir de inmediato")
    void deletingAnAdminRevokesItsToken() throws Exception {
        Store store = createStore("revoke-del");
        User admin = createAdmin(store, "revoke-del");
        User victim = createAdmin(store, "revoke-victim");
        String victimToken = loginAdmin(store, victim);
        String adminToken = loginAdmin(store, admin);

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .delete("/api/admin/users/" + victim.getId())
                        .header("Host", hostHeader(store))
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/admin/products")
                        .header("Host", hostHeader(store))
                        .header("Authorization", "Bearer " + victimToken))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Un token intacto sigue funcionando: la revocación no es un martillo")
    void anUntouchedTokenKeepsWorking() throws Exception {
        Store store = createStore("revoke-keep");
        User admin = createAdmin(store, "revoke-keep");
        String token = loginAdmin(store, admin);

        for (int i = 0; i < 3; i++) {
            mockMvc.perform(get("/api/admin/products")
                            .header("Host", hostHeader(store))
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk());
        }
    }

    // --- A8: subidas ----------------------------------------------------------------------------

    @Test
    @DisplayName("Subir HTML con nombre .png al crear un producto se rechaza con 400")
    void uploadingDisguisedHtmlIsRejected() throws Exception {
        Store store = createStore("upload-bad");
        User admin = createAdmin(store, "upload-bad");
        String token = loginAdmin(store, admin);

        MockMultipartFile disguised = new MockMultipartFile(
                "featuredImage", "inocente.png", "image/png", "<html><script>alert(1)</script></html>".getBytes());

        mockMvc.perform(productCreate(store, token).file(disguised))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Un PNG real se acepta y se guarda con extensión .png derivada de sus bytes")
    void uploadingARealImageIsAcceptedAndTypedFromItsBytes() throws Exception {
        Store store = createStore("upload-ok");
        User admin = createAdmin(store, "upload-ok");
        String token = loginAdmin(store, admin);

        // Deliberately a bogus name and content type: only the bytes should count.
        MockMultipartFile real = new MockMultipartFile(
                "featuredImage", "sin-extension", "application/octet-stream", pngBytes());

        String response = mockMvc.perform(productCreate(store, token).file(real))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        String url = objectMapper.readTree(response).get("images").get(0).get("url").asText();
        org.assertj.core.api.Assertions.assertThat(url).endsWith(".png");
    }

    @Test
    @DisplayName("Las respuestas llevan nosniff, para que /uploads/** no se reinterprete como HTML")
    void responsesCarryTheNoSniffHeader() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"));
    }

    private org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder productCreate(
            Store store, String token) {
        return (org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder)
                multipart("/api/admin/products")
                        .header("Host", hostHeader(store))
                        .header("Authorization", "Bearer " + token)
                        .param("productType", "simple")
                        .param("name", "Producto")
                        .param("active", "true")
                        .param("isFeatured", "false")
                        .param("isNew", "false")
                        .param("sortOrder", "0")
                        .param("isOnSale", "false")
                        .param("sku", "UP-" + nextSeq())
                        .param("price", "1000")
                        .param("stock", "1")
                        .param("manageStock", "true");
    }
}
