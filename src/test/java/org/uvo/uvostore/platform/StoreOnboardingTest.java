package org.uvo.uvostore.platform;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.uvo.uvostore.entity.security.Role;
import org.uvo.uvostore.support.IntegrationTestSupport;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Store onboarding (/api/platform/**): operator-only, gated by X-Platform-Key rather than the
// per-store JWT scheme (there's no tenant yet when a store is being created). Covers the two
// tenant-resolution paths TenantResolutionFilter now supports — custom domain first, subdomain
// slug as the fallback every store keeps working under regardless.
class StoreOnboardingTest extends IntegrationTestSupport {

    // Injected rather than hardcoded: app.platform-api-key lost its committed default in C4, so
    // there is no fixed literal to assert against — surefire supplies a test-only value (pom.xml).
    @Value("${app.platform-api-key}")
    private String platformKey;

    @Test
    void creatingAStoreRequiresTheCorrectPlatformKey() throws Exception {
        mockMvc.perform(post("/api/platform/stores")
                        .header("X-Platform-Key", "wrong-key")
                        .contentType("application/json")
                        .content(onboardingBody("nick" + nextSeq(), "", "Tienda de prueba")))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/platform/stores")
                        .contentType("application/json")
                        .content(onboardingBody("nick" + nextSeq(), "", "Tienda de prueba")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void creatingAStoreWithoutADomainWorksAndTheAdminCanLogInBySubdomain() throws Exception {
        String slug = "onboard-" + nextSeq();
        String response = mockMvc.perform(post("/api/platform/stores")
                        .header("X-Platform-Key", platformKey)
                        .contentType("application/json")
                        .content(onboardingBody(slug, "", "Tienda de prueba")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.slug").value(slug))
                .andReturn().getResponse().getContentAsString();

        org.junit.jupiter.api.Assertions.assertTrue(objectMapper.readTree(response).get("domain").isNull());
        String adminEmail = objectMapper.readTree(response).get("adminEmail").asText();

        mockMvc.perform(post("/api/admin/auth/login")
                        .header("Host", slug + ".localhost")
                        .contentType("application/json")
                        .content("{\"email\":\"" + adminEmail + "\",\"password\":\"password123456\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").exists());
    }

    @Test
    void creatingAStoreWithADomainResolvesTenantByThatDomain() throws Exception {
        String slug = "onboard-domain-" + nextSeq();
        String domain = "tienda" + nextSeq() + ".cl";

        String response = mockMvc.perform(post("/api/platform/stores")
                        .header("X-Platform-Key", platformKey)
                        .contentType("application/json")
                        .content(onboardingBody(slug, domain, "Tienda con dominio")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.domain").value(domain))
                .andReturn().getResponse().getContentAsString();

        String adminEmail = objectMapper.readTree(response).get("adminEmail").asText();

        // Same admin, authenticated via the CUSTOM DOMAIN instead of the subdomain — proves
        // TenantResolutionFilter's domain-match path resolves the same store.
        mockMvc.perform(post("/api/admin/auth/login")
                        .header("Host", domain)
                        .contentType("application/json")
                        .content("{\"email\":\"" + adminEmail + "\",\"password\":\"password123456\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").exists());

        // The subdomain fallback keeps working too, for the same store.
        mockMvc.perform(post("/api/admin/auth/login")
                        .header("Host", slug + ".localhost")
                        .contentType("application/json")
                        .content("{\"email\":\"" + adminEmail + "\",\"password\":\"password123456\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").exists());
    }

    @Test
    void creatingAStoreRejectsADuplicateSlug() throws Exception {
        String slug = "onboard-dup-" + nextSeq();
        mockMvc.perform(post("/api/platform/stores")
                        .header("X-Platform-Key", platformKey)
                        .contentType("application/json")
                        .content(onboardingBody(slug, "", "Primera tienda")))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/platform/stores")
                        .header("X-Platform-Key", platformKey)
                        .contentType("application/json")
                        .content(onboardingBody(slug, "", "Segunda tienda")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void creatingAStoreRejectsAnInvalidSlugFormat() throws Exception {
        mockMvc.perform(post("/api/platform/stores")
                        .header("X-Platform-Key", platformKey)
                        .contentType("application/json")
                        .content(onboardingBody("Not Valid Slug!", "", "Tienda")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void domainCanBeUpdatedAfterCreationAndRejectsADuplicate() throws Exception {
        String slugA = "onboard-upd-a-" + nextSeq();
        String slugB = "onboard-upd-b-" + nextSeq();
        String domainA = "tienda-a-" + nextSeq() + ".cl";
        String domainB = "tienda-b-" + nextSeq() + ".cl";

        String responseA = mockMvc.perform(post("/api/platform/stores")
                        .header("X-Platform-Key", platformKey)
                        .contentType("application/json")
                        .content(onboardingBody(slugA, "", "Tienda A")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        long storeIdA = objectMapper.readTree(responseA).get("storeId").asLong();

        mockMvc.perform(post("/api/platform/stores")
                        .header("X-Platform-Key", platformKey)
                        .contentType("application/json")
                        .content(onboardingBody(slugB, domainB, "Tienda B")))
                .andExpect(status().isOk());

        mockMvc.perform(put("/api/platform/stores/" + storeIdA + "/domain")
                        .header("X-Platform-Key", platformKey)
                        .contentType("application/json")
                        .content("{\"domain\":\"" + domainA + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.domain").value(domainA));

        mockMvc.perform(put("/api/platform/stores/" + storeIdA + "/domain")
                        .header("X-Platform-Key", platformKey)
                        .contentType("application/json")
                        .content("{\"domain\":\"" + domainB + "\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void theAdminOfANewStoreCanActuallyUseThePanel() throws Exception {
        // F08. El test de alta terminaba en "el login devuelve un token", y por eso este fallo llevaba
        // desde que existe el API de plataforma sin que nadie lo viera: el alta creaba el administrador
        // SIN NINGÚN ROL, así que su token traía solo ROLE_ADMIN y todos los endpoints del panel
        // respondían 403. Peor aún, el menú se dibuja a partir de los permisos, así que el dueño veía
        // un panel sin secciones —incluida la de Roles, la única que lo habría arreglado.
        String slug = "onboard-usable-" + nextSeq();
        String response = mockMvc.perform(post("/api/platform/stores")
                        .header("X-Platform-Key", platformKey)
                        .contentType("application/json")
                        .content(onboardingBody(slug, "", "Tienda usable")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String adminEmail = objectMapper.readTree(response).get("adminEmail").asText();
        long storeId = objectMapper.readTree(response).get("storeId").asLong();

        String login = mockMvc.perform(post("/api/admin/auth/login")
                        .header("Host", slug + ".localhost")
                        .contentType("application/json")
                        .content("{\"email\":\"" + adminEmail + "\",\"password\":\"password123456\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String token = objectMapper.readTree(login).get("token").asText();

        // Leer. Las aserciones sobre los endpoints van primero a propósito: son el síntoma que sufre el
        // cliente, y así un fallo se lee como "403 al abrir el panel" y no como un detalle del token.
        mockMvc.perform(get("/api/admin/products")
                        .header("Host", slug + ".localhost")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        // Y escribir: un permiso de lectura por sí solo no haría gestionable la tienda. Se usa un
        // endpoint con cuerpo JSON (crear un cupón exige 'coupons.manage'); el de categorías es
        // multipart y aquí lo que se comprueba es el permiso, no el formato.
        mockMvc.perform(post("/api/admin/coupons")
                        .header("Host", slug + ".localhost")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content("{\"code\":\"ALTA-" + nextSeq() + "\",\"name\":\"Cupón inicial\","
                                + "\"type\":\"FIXED\",\"value\":1000,\"active\":true}"))
                .andExpect(status().is2xxSuccessful());

        // Y lo que decide qué secciones dibuja el panel: con la lista vacía solo queda el Dashboard.
        assertThat(objectMapper.readTree(login).get("permissions")).isNotEmpty();

        // El rol es de esta tienda y trae el catálogo completo — contado, no un número fijo que se
        // quede viejo en cuanto una migración añada un permiso.
        List<Role> roles = roleRepository.findByStoreId(storeId);
        assertThat(roles).singleElement().satisfies(role -> {
            assertThat(role.getName()).isEqualTo("Administrador");
            assertThat(role.getPermissions()).hasSize((int) permissionRepository.count());
        });
    }

    private String onboardingBody(String slug, String domain, String storeName) {
        return """
                {
                  "slug": "%s",
                  "domain": "%s",
                  "storeName": "%s",
                  "adminName": "Admin de prueba",
                  "adminEmail": "admin-%d@test.local",
                  "adminPassword": "password123456"
                }
                """.formatted(slug, domain, storeName, nextSeq());
    }
}
