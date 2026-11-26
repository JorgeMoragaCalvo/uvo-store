package org.uvo.uvostore.pos;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.uvo.uvostore.entity.pos.PosConnection;
import org.uvo.uvostore.entity.pos.PosConnectionKey;
import org.uvo.uvostore.entity.security.User;
import org.uvo.uvostore.entity.tenant.Store;
import org.uvo.uvostore.repository.PosConnectionKeyRepository;
import org.uvo.uvostore.repository.PosConnectionRepository;
import org.uvo.uvostore.support.AuditRows;
import org.uvo.uvostore.support.IntegrationTestSupport;
import org.uvo.uvostore.support.PosSigning;
import org.uvo.uvostore.support.WithPosIntegration;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * PROD-12 e IMP-08. La conexión con UvoPOS desde Ajustes: la empresa, la URL y las dos llaves del contrato (§2). Solo
 * queda activa con una llave vigente en cada dirección; un {@code key_id} nuevo es una rotación (la anterior vive 7
 * días) y una llave se puede revocar en el acto.
 */
@WithPosIntegration
class PosConnectionActivationTest extends IntegrationTestSupport {

    @Autowired
    private PosConnectionRepository posConnectionRepository;
    @Autowired
    private PosConnectionKeyRepository keyRepository;

    @Test
    @DisplayName("Sin las dos llaves la conexión se rechaza y no queda creada")
    void withoutBothKeysTheConnectionIsRejected() throws Exception {
        Fixture fixture = fixture("pos-nokeys");
        long company = nextSeq();

        mockMvc.perform(settingsPut(fixture, company, "out-" + company, PosSigning.secret("o" + company), "", ""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("dos llaves")));

        assertThat(posConnectionRepository.findByStoreId(fixture.store().getId())).isEmpty();
    }

    @Test
    @DisplayName("Un secreto que no es base64 de 32 bytes se rechaza sin repetirlo")
    void aWeakSecretIsRejected() throws Exception {
        Fixture fixture = fixture("pos-weak");
        long company = nextSeq();

        String response = mockMvc.perform(settingsPut(fixture, company, "out-" + company, "corto",
                        "in-" + company, PosSigning.secret("i" + company)))
                .andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString();

        assertThat(response).contains("32").doesNotContain("\"corto\"");
    }

    @Test
    @DisplayName("Con las dos llaves la conexión queda activa, y el panel ve los key_id, nunca los secretos")
    void bothKeysActivateTheConnection() throws Exception {
        Fixture fixture = fixture("pos-keys");
        long company = nextSeq();
        String outSecret = PosSigning.secret("o" + company);
        String inSecret = PosSigning.secret("i" + company);

        String response = mockMvc.perform(settingsPut(fixture, company, "out-" + company, outSecret, "in-" + company,
                        inSecret))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.posCompanyId").value(company))
                .andExpect(jsonPath("$.posOutboundKeyId").value("out-" + company))
                .andExpect(jsonPath("$.posInboundKeyId").value("in-" + company))
                .andExpect(jsonPath("$.posConnectionActive").value(true))
                .andReturn().getResponse().getContentAsString();

        assertThat(response).doesNotContain(outSecret).doesNotContain(inSecret);
        PosConnection connection = posConnectionRepository.findByStoreId(fixture.store().getId()).orElseThrow();
        assertThat(connection.isActive()).isTrue();
        assertThat(connection.getApiUrl()).isEqualTo("https://pos.test");

        // Un guardado que repite los key_id sin secretos (el panel no puede pre-rellenarlos) no cambia nada.
        mockMvc.perform(settingsPut(fixture, company, "out-" + company, "", "in-" + company, ""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.posConnectionActive").value(true));
        assertThat(keyRepository.findByConnection(connection.getId(), PosConnectionKey.Direction.INBOUND)).hasSize(1);
    }

    @Test
    @DisplayName("Un key_id nuevo es una rotación: la llave anterior sigue viva 7 días")
    void aNewKeyIdRotates() throws Exception {
        Fixture fixture = fixture("pos-rotate");
        long company = nextSeq();
        mockMvc.perform(settingsPut(fixture, company, "out-" + company, PosSigning.secret("o" + company),
                "in-" + company, PosSigning.secret("i" + company))).andExpect(status().isOk());

        mockMvc.perform(settingsPut(fixture, company, "", "", "in2-" + company, PosSigning.secret("i2" + company)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.posInboundKeyId").value("in2-" + company));

        PosConnectionKey old = keyRepository.findWithConnection(PosConnectionKey.Direction.INBOUND, "in-" + company)
                .orElseThrow();
        assertThat(old.getRetiresAt()).isBetween(Instant.now().plus(Duration.ofDays(6)),
                Instant.now().plus(Duration.ofDays(8)));
        assertThat(old.isUsableAt(Instant.now())).isTrue();
    }

    @Test
    @DisplayName("Revocar la única llave de una dirección la mata y deja la conexión inactiva, con auditoría")
    void revokingTheOnlyKeyDeactivatesTheConnection() throws Exception {
        Fixture fixture = fixture("pos-revoke");
        long company = nextSeq();
        mockMvc.perform(settingsPut(fixture, company, "out-" + company, PosSigning.secret("o" + company),
                "in-" + company, PosSigning.secret("i" + company))).andExpect(status().isOk());

        mockMvc.perform(post("/api/admin/pos/keys/revoke")
                        .header("Host", hostHeader(fixture.store()))
                        .header("Authorization", "Bearer " + fixture.token())
                        .contentType("application/json")
                        .content("{\"direction\":\"INBOUND\",\"keyId\":\"in-" + company + "\"}"))
                .andExpect(status().isOk());

        PosConnection connection = posConnectionRepository.findByStoreId(fixture.store().getId()).orElseThrow();
        assertThat(connection.isActive()).isFalse();
        assertThat(keyRepository.findWithConnection(PosConnectionKey.Direction.INBOUND, "in-" + company).orElseThrow()
                .getRevokedAt()).isNotNull();
        assertThat(AuditRows.single(jdbcTemplate, fixture.store().getId(), "POS_KEY_REVOKE").details())
                .contains("in-" + company).doesNotContain(PosSigning.secret("i" + company));

        mockMvc.perform(get("/api/admin/pos/keys")
                        .header("Host", hostHeader(fixture.store()))
                        .header("Authorization", "Bearer " + fixture.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.keyId == 'in-" + company + "')].usable").value(false));
    }

    @Test
    @DisplayName("Una conexión activa sin llaves (anterior a V38) no autentica nada: responde 401, no revienta")
    void aConnectionWithoutKeysAnswers401() throws Exception {
        Store store = createStore("pos-legacy");
        PosConnection connection = new PosConnection();
        connection.setStore(store);
        connection.setCompanyId(nextSeq());
        connection.setCompanyName("UvoPOS");
        connection.setApiUrl("https://pos.invalid");
        connection.setActive(true);
        posConnectionRepository.save(connection);

        mockMvc.perform(PosSigning.signedPost("/api/webhooks/pos/stock-updated",
                        "{\"companyId\":" + connection.getCompanyId() + "}", connection))
                .andExpect(status().isUnauthorized());
    }

    private record Fixture(Store store, String token) {
    }

    private Fixture fixture(String prefix) throws Exception {
        Store store = createStore(prefix);
        User admin = createAdmin(store, prefix);
        return new Fixture(store, loginAdmin(store, admin));
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder settingsPut(
            Fixture fixture, long company, String outKeyId, String outSecret, String inKeyId, String inSecret) {
        return put("/api/admin/settings/general")
                .header("Host", hostHeader(fixture.store()))
                .header("Authorization", "Bearer " + fixture.token())
                .contentType("application/json")
                .content("""
                        {
                          "storeName":"Tienda","storeEmail":"tienda@test.local","storePhone":"","adminEmail":"admin@test.local",
                          "currency":"CLP","currencySymbol":"$","taxRate":"19","pricesIncludeTax":true,
                          "shippingEnabled":true,"defaultShippingCost":"0","freeShippingEnabled":false,"freeShippingThreshold":"0",
                          "allowGuestCheckout":true,"requirePhone":false,"requireCompany":false,
                          "stripePublicKey":"","stripeSecretKey":"","stripeEnabled":false,
                          "posApiUrl":"https://pos.test","posCompanyId":%d,
                          "posOutboundKeyId":"%s","posOutboundSecret":"%s","posInboundKeyId":"%s","posInboundSecret":"%s",
                          "posSyncEnabled":true,
                          "metaTitle":"","metaDescription":"","metaKeywords":"","facebookUrl":"","instagramUrl":"","twitterUrl":""
                        }
                        """.formatted(company, outKeyId, outSecret, inKeyId, inSecret));
    }
}
