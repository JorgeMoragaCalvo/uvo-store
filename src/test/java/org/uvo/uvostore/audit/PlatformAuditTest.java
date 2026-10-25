package org.uvo.uvostore.audit;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.uvo.uvostore.config.RequestIdFilter;
import org.uvo.uvostore.entity.security.User;
import org.uvo.uvostore.entity.tenant.Store;
import org.uvo.uvostore.support.AuditRows;
import org.uvo.uvostore.support.IntegrationTestSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * PROD-18. Lo que hace la API de plataforma queda a nombre de la plataforma y del operador que lo pidió
 * ({@code X-Platform-Operator}, el operador del backoffice del POS), y en la tienda sobre la que recae.
 */
class PlatformAuditTest extends IntegrationTestSupport {

    @Value("${app.platform-api-key}")
    private String platformKey;

    @Test
    @DisplayName("El alta queda a nombre de la plataforma y del operador, con la tienda nueva y el id de la petición")
    void theOnboardingIsAttributedToTheOperator() throws Exception {
        String slug = "audit-op-" + nextSeq();

        MvcResult result = mockMvc.perform(onboarding(slug).header("X-Platform-Operator", "op-123"))
                .andExpect(status().isOk())
                .andReturn();

        long storeId = storeIdOf(result);
        AuditRows.Row row = AuditRows.single(jdbcTemplate, storeId, "STORE_CREATE");
        assertThat(row.actorType()).isEqualTo("PLATFORM");
        assertThat(row.actorId()).isNull();
        assertThat(row.actorLabel()).isEqualTo("op-123");
        assertThat(row.targetType()).isEqualTo("STORE");
        assertThat(row.targetId()).isEqualTo(storeId);
        assertThat(row.requestId()).isEqualTo(result.getResponse().getHeader(RequestIdFilter.HEADER));
        JsonNode details = row.json();
        assertThat(details.path("slug").asText()).isEqualTo(slug);
        assertThat(details.path("origin").asText()).isEqualTo("OPERATOR");
        assertThat(details.path("ownerEmail").asText()).startsWith("dueno-");
    }

    @Test
    @DisplayName("Sin la cabecera, la fila es de la plataforma sin operador (el formulario de operador no la manda)")
    void withoutTheHeaderThereIsNoOperator() throws Exception {
        MvcResult result = mockMvc.perform(onboarding("audit-noop-" + nextSeq()))
                .andExpect(status().isOk())
                .andReturn();

        AuditRows.Row row = AuditRows.single(jdbcTemplate, storeIdOf(result), "STORE_CREATE");
        assertThat(row.actorType()).isEqualTo("PLATFORM");
        assertThat(row.actorLabel()).isNull();
    }

    @Test
    @DisplayName("Un operador mal formado o repetido es un 400 y no se crea nada")
    void aMalformedOperatorIsRejected() throws Exception {
        String slug = "audit-bad-op-" + nextSeq();

        mockMvc.perform(onboarding(slug).header("X-Platform-Operator", "op con espacios"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("X-Platform-Operator")));
        mockMvc.perform(onboarding(slug).header("X-Platform-Operator", "op-uno", "op-dos"))
                .andExpect(status().isBadRequest());

        assertThat(storeRepository.existsBySlug(slug)).isFalse();
    }

    @Test
    @DisplayName("Con la clave de plataforma y además el Bearer de un admin, quien actúa sigue siendo la plataforma")
    void aBearerDoesNotTurnThePlatformIntoAnAdmin() throws Exception {
        Store other = createStore("audit-bearer");
        User admin = createAdmin(other, "audit-bearer");
        String token = loginAdmin(other, admin);

        MvcResult result = mockMvc.perform(onboarding("audit-bearer-new-" + nextSeq())
                        .header("Authorization", "Bearer " + token)
                        .header("X-Platform-Operator", "op-7"))
                .andExpect(status().isOk())
                .andReturn();

        AuditRows.Row row = AuditRows.single(jdbcTemplate, storeIdOf(result), "STORE_CREATE");
        assertThat(row.actorType()).isEqualTo("PLATFORM");
        assertThat(row.actorId()).isNull();
        assertThat(row.actorLabel()).isEqualTo("op-7");
    }

    @Test
    @DisplayName("Con el Host de otra tienda, la fila es de la tienda creada y no de la del Host")
    void theRowBelongsToTheCreatedStoreNotToTheHost() throws Exception {
        Store hostStore = createStore("audit-host");

        MvcResult result = mockMvc.perform(onboarding("audit-host-new-" + nextSeq())
                        .header("Host", hostHeader(hostStore)))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(AuditRows.forStore(jdbcTemplate, storeIdOf(result), "STORE_CREATE")).hasSize(1);
        assertThat(auditRows(hostStore)).isEmpty();
    }

    @Test
    @DisplayName("Cambiar el dominio y verificarlo dejan fila; repetir lo mismo no")
    void domainChangesAreRecordedOnlyWhenTheyChangeSomething() throws Exception {
        long storeId = storeIdOf(mockMvc.perform(onboarding("audit-domain-" + nextSeq()))
                .andExpect(status().isOk())
                .andReturn());
        String domain = "audit-" + nextSeq() + ".example.com";

        for (int i = 0; i < 2; i++) {
            mockMvc.perform(platform(put("/api/platform/stores/" + storeId + "/domain"))
                            .header("X-Platform-Operator", "op-dominio")
                            .content("{\"domain\":\"" + domain + "\"}"))
                    .andExpect(status().isOk());
            mockMvc.perform(platform(put("/api/platform/stores/" + storeId + "/domain/verified"))
                            .content("{\"verified\":true}"))
                    .andExpect(status().isOk());
        }

        AuditRows.Row update = AuditRows.single(jdbcTemplate, storeId, "STORE_DOMAIN_UPDATE");
        assertThat(update.actorLabel()).isEqualTo("op-dominio");
        assertThat(update.change("domain").path("from").isNull()).isTrue();
        assertThat(update.change("domain").path("to").asText()).isEqualTo(domain);
        AuditRows.Row verify = AuditRows.single(jdbcTemplate, storeId, "STORE_DOMAIN_VERIFY");
        assertThat(verify.change("verified").path("to").asBoolean()).isTrue();
        assertThat(verify.json().path("domain").asText()).isEqualTo(domain);
    }

    private MockHttpServletRequestBuilder onboarding(String slug) {
        return platform(post("/api/platform/stores"))
                .content("""
                        {"slug":"%s","domain":"","storeName":"Tienda auditada","adminName":"Dueno",
                         "adminEmail":"dueno-%d@test.local"}""".formatted(slug, nextSeq()));
    }

    private MockHttpServletRequestBuilder platform(MockHttpServletRequestBuilder builder) {
        return builder.header("X-Platform-Key", platformKey).contentType("application/json");
    }

    private long storeIdOf(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("storeId").asLong();
    }
}
