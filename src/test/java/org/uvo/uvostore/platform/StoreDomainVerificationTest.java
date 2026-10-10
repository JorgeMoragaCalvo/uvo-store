package org.uvo.uvostore.platform;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.uvo.uvostore.support.IntegrationTestSupport;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * PROD-04. El dominio propio de una tienda no se usa en los enlaces que salen hasta que se comprueba.
 *
 * <p>La asimetría es el punto: de <b>entrada</b> el dominio vale desde que se escribe
 * ({@code StoreHostResolver}, y eso lo cubre {@code StoreOnboardingTest}), porque si no no habría forma de
 * comprobar que funciona antes de marcarlo; de <b>salida</b> solo vale verificado, porque un correo con un
 * enlace a un dominio cuyo DNS todavía no apunta aquí es un correo inservible y no hay pantalla para
 * reenviarlo.
 */
class StoreDomainVerificationTest extends IntegrationTestSupport {

    @Value("${app.platform-api-key}")
    private String platformKey;

    @Test
    @DisplayName("El alta entrega las direcciones concretas, y un dominio nuevo nace sin verificar")
    void onboardingReturnsTheConcreteUrlsAndAnUnverifiedDomain() throws Exception {
        String slug = "verif-" + nextSeq();
        String domain = "verif-" + nextSeq() + ".example";

        mockMvc.perform(create(slug, domain))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.domain").value(domain))
                .andExpect(jsonPath("$.domainVerifiedAt").doesNotExist())
                // Las dos URLs que el §6.2 del plan pide entregar, y que el operador tenía que componer de
                // memoria. Con el dominio sin verificar son las del subdominio, que funciona siempre.
                .andExpect(jsonPath("$.storefrontUrl").value("http://" + slug + ".localhost"))
                .andExpect(jsonPath("$.adminUrl").value("http://" + slug + ".localhost/admin/login"));
    }

    @Test
    @DisplayName("Verificar pasa las direcciones al dominio propio, y desverificar las devuelve")
    void verifyingSwitchesTheUrlsToTheCustomDomain() throws Exception {
        String slug = "verif-on-" + nextSeq();
        String domain = "verif-on-" + nextSeq() + ".example";
        long storeId = createAndGetId(slug, domain);

        mockMvc.perform(setVerified(storeId, true))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.domainVerifiedAt").exists())
                .andExpect(jsonPath("$.storefrontUrl").value("http://" + domain))
                .andExpect(jsonPath("$.adminUrl").value("http://" + domain + "/admin/login"));

        // Deshacerlo tiene que ser posible sin borrar el dominio: es la salida cuando un dominio deja de
        // funcionar y hay que devolver los enlaces al subdominio.
        mockMvc.perform(setVerified(storeId, false))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.domain").value(domain))
                .andExpect(jsonPath("$.domainVerifiedAt").doesNotExist())
                .andExpect(jsonPath("$.storefrontUrl").value("http://" + slug + ".localhost"));
    }

    @Test
    @DisplayName("Cambiar el dominio pierde la verificación: la nueva no se hereda")
    void changingTheDomainClearsTheVerification() throws Exception {
        String slug = "verif-chg-" + nextSeq();
        String first = "verif-chg-a" + nextSeq() + ".example";
        String second = "verif-chg-b" + nextSeq() + ".example";
        long storeId = createAndGetId(slug, first);

        mockMvc.perform(setVerified(storeId, true)).andExpect(status().isOk());

        // Sin esto, el dominio nuevo saldría en los correos desde el primer segundo, antes de que nadie
        // haya comprobado su DNS — que es exactamente lo que la columna existe para evitar.
        mockMvc.perform(put("/api/platform/stores/" + storeId + "/domain")
                        .header("X-Platform-Key", platformKey)
                        .contentType("application/json")
                        .content("{\"domain\":\"" + second + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.domain").value(second))
                .andExpect(jsonPath("$.domainVerifiedAt").doesNotExist())
                .andExpect(jsonPath("$.storefrontUrl").value("http://" + slug + ".localhost"));
    }

    @Test
    @DisplayName("No se puede verificar una tienda que no tiene dominio propio")
    void aStoreWithoutACustomDomainCannotBeVerified() throws Exception {
        long storeId = createAndGetId("verif-sin-" + nextSeq(), "");

        mockMvc.perform(setVerified(storeId, true))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Esta tienda no tiene dominio propio que verificar"));
    }

    @Test
    @DisplayName("Verificar es operación de plataforma: sin la clave, 401")
    void verifyingRequiresThePlatformKey() throws Exception {
        long storeId = createAndGetId("verif-key-" + nextSeq(), "verif-key" + nextSeq() + ".example");

        mockMvc.perform(put("/api/platform/stores/" + storeId + "/domain/verified")
                        .contentType("application/json")
                        .content("{\"verified\":true}"))
                .andExpect(status().isUnauthorized());
    }

    // --- helpers -----------------------------------------------------------------------------------

    private long createAndGetId(String slug, String domain) throws Exception {
        String response = mockMvc.perform(create(slug, domain))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).get("storeId").asLong();
    }

    private org.springframework.test.web.servlet.RequestBuilder create(String slug, String domain) {
        return post("/api/platform/stores")
                .header("X-Platform-Key", platformKey)
                .contentType("application/json")
                .content("""
                        {
                          "slug": "%s",
                          "domain": "%s",
                          "storeName": "Tienda de prueba",
                          "adminName": "Admin de prueba",
                          "adminEmail": "admin-%d@test.local",
                          "adminPassword": "password123456"
                        }
                        """.formatted(slug, domain, nextSeq()));
    }

    private org.springframework.test.web.servlet.RequestBuilder setVerified(long storeId, boolean verified) {
        return put("/api/platform/stores/" + storeId + "/domain/verified")
                .header("X-Platform-Key", platformKey)
                .contentType("application/json")
                .content("{\"verified\":" + verified + "}");
    }
}
