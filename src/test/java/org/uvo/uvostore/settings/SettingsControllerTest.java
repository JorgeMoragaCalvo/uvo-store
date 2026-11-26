package org.uvo.uvostore.settings;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.uvo.uvostore.entity.security.User;
import org.uvo.uvostore.entity.tenant.Store;
import org.uvo.uvostore.support.IntegrationTestSupport;
import org.uvo.uvostore.support.PosSigning;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// C3 fix — GET /api/admin/settings/general never echoes the Stripe secret key or the POS key secrets in plaintext
// (only a "*Configured" flag or the key_id), a blank secret on PUT preserves whatever is already stored instead of
// wiping it, and the values are encrypted at rest (settings.value for stripe_secret_key, pos_connection_keys.secret).
class SettingsControllerTest extends IntegrationTestSupport {

    @PersistenceContext
    private EntityManager entityManager;

    private String fullUpdateBody(String stripeSecretKey, Long posCompanyId, String outKeyId, String outSecret,
                                  String inKeyId, String inSecret) {
        // F17: `stripeEnabled` sale de si este cuerpo trae clave secreta, en vez de ir fijo en true.
        return """
                {
                  "storeName":"Tienda","storeEmail":"tienda@test.local","storePhone":"","adminEmail":"admin@test.local",
                  "currency":"CLP","currencySymbol":"$","taxRate":"19","pricesIncludeTax":true,
                  "shippingEnabled":true,"defaultShippingCost":"0","freeShippingEnabled":false,"freeShippingThreshold":"0",
                  "allowGuestCheckout":true,"requirePhone":false,"requireCompany":false,
                  "stripePublicKey":"pk_test_123","stripeSecretKey":"%s","stripeEnabled":%s,
                  "posApiUrl":"https://pos.test","posCompanyId":%s,
                  "posOutboundKeyId":"%s","posOutboundSecret":"%s","posInboundKeyId":"%s","posInboundSecret":"%s",
                  "posSyncEnabled":%s,
                  "metaTitle":"","metaDescription":"","metaKeywords":"","facebookUrl":"","instagramUrl":"","twitterUrl":""
                }
                """.formatted(stripeSecretKey, !stripeSecretKey.isBlank(), posCompanyId, outKeyId, outSecret, inKeyId,
                inSecret, posCompanyId != null);
    }

    @Test
    void generalSettingsNeverEchoesRawSecretsInTheResponse() throws Exception {
        Store store = createStore("settings-echo");
        User admin = createAdmin(store, "settings-echo");
        String token = loginAdmin(store, admin);
        long company = nextSeq();

        String stripeSecret = "sk_test_super_secret_value_12345";
        String outSecret = PosSigning.secret("echo-out-" + company);
        String inSecret = PosSigning.secret("echo-in-" + company);

        String response = mockMvc.perform(put("/api/admin/settings/general")
                        .header("Host", hostHeader(store))
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content(fullUpdateBody(stripeSecret, company, "out-" + company, outSecret, "in-" + company,
                                inSecret)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stripeSecretKeyConfigured").value(true))
                .andExpect(jsonPath("$.posConnectionActive").value(true))
                .andReturn().getResponse().getContentAsString();

        assertFalse(response.contains(stripeSecret), "response must never echo the Stripe secret key: " + response);
        assertFalse(response.contains(outSecret), "response must never echo a POS key secret: " + response);
        assertFalse(response.contains(inSecret), "response must never echo a POS key secret: " + response);

        String fetched = mockMvc.perform(get("/api/admin/settings/general")
                        .header("Host", hostHeader(store))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stripeSecretKeyConfigured").value(true))
                .andExpect(jsonPath("$.posOutboundKeyId").value("out-" + company))
                .andExpect(jsonPath("$.posInboundKeyId").value("in-" + company))
                .andReturn().getResponse().getContentAsString();
        assertFalse(fetched.contains(outSecret) || fetched.contains(inSecret), "GET must never echo a POS secret");
    }

    @Test
    void blankSecretsOnUpdatePreserveTheExistingStoredValue() throws Exception {
        Store store = createStore("settings-preserve");
        User admin = createAdmin(store, "settings-preserve");
        String token = loginAdmin(store, admin);
        long company = nextSeq();

        mockMvc.perform(put("/api/admin/settings/general")
                        .header("Host", hostHeader(store))
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content(fullUpdateBody("sk_test_original_value", company, "out-" + company,
                                PosSigning.secret("o" + company), "in-" + company, PosSigning.secret("i" + company))))
                .andExpect(status().isOk());

        // Second save omits the secrets (blank) — must not wipe what's already configured.
        mockMvc.perform(put("/api/admin/settings/general")
                        .header("Host", hostHeader(store))
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content(fullUpdateBody("", company, "out-" + company, "", "in-" + company, "")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stripeSecretKeyConfigured").value(true))
                .andExpect(jsonPath("$.posConnectionActive").value(true))
                .andExpect(jsonPath("$.posOutboundKeyId").value("out-" + company));
    }

    @Test
    void stripeSecretKeyIsEncryptedAtRestNotPlaintext() throws Exception {
        Store store = createStore("settings-encrypt-stripe");
        User admin = createAdmin(store, "settings-encrypt-stripe");
        String token = loginAdmin(store, admin);

        String stripeSecret = "sk_test_plaintext_should_never_appear_in_db_12345";

        mockMvc.perform(put("/api/admin/settings/general")
                        .header("Host", hostHeader(store))
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content(fullUpdateBody(stripeSecret, null, "", "", "", "")))
                .andExpect(status().isOk());

        entityManager.flush();
        String rawValue = (String) entityManager.createNativeQuery(
                        "SELECT value FROM settings WHERE store_id = :storeId AND key = 'stripe_secret_key'")
                .setParameter("storeId", store.getId())
                .getSingleResult();

        assertFalse(rawValue.contains(stripeSecret), "settings.value must not contain the plaintext Stripe secret key: " + rawValue);
    }

    @Test
    void posKeySecretsAreEncryptedAtRestNotPlaintext() throws Exception {
        Store store = createStore("settings-encrypt-pos");
        User admin = createAdmin(store, "settings-encrypt-pos");
        String token = loginAdmin(store, admin);
        long company = nextSeq();
        String outSecret = PosSigning.secret("plain-out-" + company);
        String inSecret = PosSigning.secret("plain-in-" + company);

        mockMvc.perform(put("/api/admin/settings/general")
                        .header("Host", hostHeader(store))
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content(fullUpdateBody("", company, "out-" + company, outSecret, "in-" + company, inSecret)))
                .andExpect(status().isOk());

        entityManager.flush();
        @SuppressWarnings("unchecked")
        List<String> stored = entityManager.createNativeQuery(
                        "SELECT k.secret FROM pos_connection_keys k JOIN pos_connections c ON c.id = k.connection_id "
                                + "WHERE c.store_id = :storeId")
                .setParameter("storeId", store.getId())
                .getResultList();

        for (String value : stored) {
            assertFalse(value.contains(outSecret) || value.contains(inSecret),
                    "pos_connection_keys.secret must not contain a plaintext secret: " + value);
        }
        assertFalse(stored.isEmpty(), "the two keys are stored");
    }
}
