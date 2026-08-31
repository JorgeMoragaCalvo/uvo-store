package org.uvo.uvostore.settings;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.uvo.uvostore.entity.security.User;
import org.uvo.uvostore.entity.tenant.Store;
import org.uvo.uvostore.support.IntegrationTestSupport;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// C3 fix — GET /api/admin/settings/general never echoes the Stripe secret key / POS API
// token / POS webhook secret in plaintext (only a "*Configured" flag, same pattern as
// PaymentGatewayConfigDto.credentialsSet), a blank secret on PUT preserves whatever is already
// stored instead of wiping it, and the values are encrypted at rest (settings.value for
// stripe_secret_key, pos_connections.api_key/webhook_secret).
class SettingsControllerTest extends IntegrationTestSupport {

    @PersistenceContext
    private EntityManager entityManager;

    private String fullUpdateBody(String stripeSecretKey, String posApiToken, String posWebhookSecret) {
        return """
                {
                  "storeName":"Tienda","storeEmail":"tienda@test.local","storePhone":"","adminEmail":"admin@test.local",
                  "currency":"CLP","currencySymbol":"$","taxRate":"19","pricesIncludeTax":true,
                  "shippingEnabled":true,"defaultShippingCost":"0","freeShippingEnabled":false,"freeShippingThreshold":"0",
                  "allowGuestCheckout":true,"requirePhone":false,"requireCompany":false,
                  "stripePublicKey":"pk_test_123","stripeSecretKey":"%s","stripeEnabled":true,
                  "posApiUrl":"https://pos.test","posApiToken":"%s","posWebhookSecret":"%s","posSyncEnabled":%s,
                  "metaTitle":"","metaDescription":"","metaKeywords":"","facebookUrl":"","instagramUrl":"","twitterUrl":""
                }
                """.formatted(stripeSecretKey, posApiToken, posWebhookSecret, !posApiToken.isBlank());
    }

    @Test
    void generalSettingsNeverEchoesRawSecretsInTheResponse() throws Exception {
        Store store = createStore("settings-echo");
        User admin = createAdmin(store, "settings-echo");
        String token = loginAdmin(store, admin);

        String stripeSecret = "sk_test_super_secret_value_12345";
        String posToken = "uvp_" + store.getId() + "_abc123";
        String webhookSecret = "whsec_super_secret_value_12345";

        String response = mockMvc.perform(put("/api/admin/settings/general")
                        .header("Host", hostHeader(store))
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content(fullUpdateBody(stripeSecret, posToken, webhookSecret)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stripeSecretKeyConfigured").value(true))
                .andExpect(jsonPath("$.posApiTokenConfigured").value(true))
                .andExpect(jsonPath("$.posWebhookSecretConfigured").value(true))
                .andReturn().getResponse().getContentAsString();

        assertFalse(response.contains(stripeSecret), "response must never echo the Stripe secret key: " + response);
        assertFalse(response.contains(posToken), "response must never echo the POS API token: " + response);
        assertFalse(response.contains(webhookSecret), "response must never echo the POS webhook secret: " + response);

        mockMvc.perform(get("/api/admin/settings/general")
                        .header("Host", hostHeader(store))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stripeSecretKeyConfigured").value(true))
                .andExpect(jsonPath("$.posApiTokenConfigured").value(true))
                .andExpect(jsonPath("$.posWebhookSecretConfigured").value(true));
    }

    @Test
    void blankSecretsOnUpdatePreserveTheExistingStoredValue() throws Exception {
        Store store = createStore("settings-preserve");
        User admin = createAdmin(store, "settings-preserve");
        String token = loginAdmin(store, admin);

        String stripeSecret = "sk_test_original_value";
        String posToken = "uvp_" + store.getId() + "_original";
        String webhookSecret = "whsec_original_value";

        mockMvc.perform(put("/api/admin/settings/general")
                        .header("Host", hostHeader(store))
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content(fullUpdateBody(stripeSecret, posToken, webhookSecret)))
                .andExpect(status().isOk());

        // Second save omits the secrets (blank) — must not wipe what's already configured.
        mockMvc.perform(put("/api/admin/settings/general")
                        .header("Host", hostHeader(store))
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content(fullUpdateBody("", "", "")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stripeSecretKeyConfigured").value(true))
                .andExpect(jsonPath("$.posApiTokenConfigured").value(true))
                .andExpect(jsonPath("$.posWebhookSecretConfigured").value(true));
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
                        .content(fullUpdateBody(stripeSecret, "", "")))
                .andExpect(status().isOk());

        entityManager.flush();
        String rawValue = (String) entityManager.createNativeQuery(
                        "SELECT value FROM settings WHERE store_id = :storeId AND key = 'stripe_secret_key'")
                .setParameter("storeId", store.getId())
                .getSingleResult();

        assertFalse(rawValue.contains(stripeSecret), "settings.value must not contain the plaintext Stripe secret key: " + rawValue);
    }

    @Test
    void posConnectionCredentialsAreEncryptedAtRestNotPlaintext() throws Exception {
        Store store = createStore("settings-encrypt-pos");
        User admin = createAdmin(store, "settings-encrypt-pos");
        String token = loginAdmin(store, admin);

        String posToken = "uvp_" + store.getId() + "_plaintextshouldnotappear12345";
        String webhookSecret = "whsec_plaintext_should_never_appear_in_db_12345";

        mockMvc.perform(put("/api/admin/settings/general")
                        .header("Host", hostHeader(store))
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content(fullUpdateBody("", posToken, webhookSecret)))
                .andExpect(status().isOk());

        entityManager.flush();
        Object[] row = (Object[]) entityManager.createNativeQuery(
                        "SELECT api_key, webhook_secret FROM pos_connections WHERE store_id = :storeId")
                .setParameter("storeId", store.getId())
                .getSingleResult();

        assertFalse(((String) row[0]).contains(posToken), "pos_connections.api_key must not contain the plaintext token: " + row[0]);
        assertFalse(((String) row[1]).contains(webhookSecret), "pos_connections.webhook_secret must not contain the plaintext secret: " + row[1]);
    }
}
