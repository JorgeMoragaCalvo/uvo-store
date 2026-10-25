package org.uvo.uvostore.audit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.uvo.uvostore.entity.security.User;
import org.uvo.uvostore.entity.tenant.Store;
import org.uvo.uvostore.support.AuditRows;
import org.uvo.uvostore.support.IntegrationTestSupport;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * PROD-18. «Ajustes generales» se guarda entero cada vez; la auditoría deja solo lo que cambió, en tres grupos: dinero
 * (F17), Stripe y la conexión con el POS. Lo demás (nombre, SEO, redes) no se audita.
 */
class SettingsAuditTest extends IntegrationTestSupport {

    private Store store;
    private String token;

    @BeforeEach
    void setUp() throws Exception {
        store = createStore("audit-settings");
        User admin = createAdmin(store, "audit-settings");
        token = loginAdmin(store, admin);
    }

    @Test
    @DisplayName("Guardar dos veces lo mismo deja una fila por grupo (la primera), no dos")
    void savingTheSameTwiceRecordsOnce() throws Exception {
        save(new Body());
        save(new Body());

        assertThat(auditRows(store, "SETTINGS_MONEY_UPDATE")).hasSize(1);
        assertThat(auditRows(store, "SETTINGS_STRIPE_UPDATE")).hasSize(1);
        assertThat(auditRows(store, "POS_CONNECTION_UPDATE")).hasSize(1);
    }

    @Test
    @DisplayName("Cambiar solo el nombre de la tienda no deja fila")
    void aNonAuditedChangeLeavesNoRow() throws Exception {
        save(new Body());
        int before = auditRows(store).size();

        Body renamed = new Body();
        renamed.storeName = "Otro nombre";
        save(renamed);

        assertThat(auditRows(store)).hasSize(before);
    }

    @Test
    @DisplayName("Un cambio de IVA deja el antes y el después, y nada más")
    void aTaxRateChangeKeepsFromAndTo() throws Exception {
        save(new Body());
        Body changed = new Body();
        changed.taxRate = "10";
        save(changed);

        List<AuditRows.Row> rows = auditRows(store, "SETTINGS_MONEY_UPDATE");
        assertThat(rows).hasSize(2);
        AuditRows.Row row = rows.get(1);
        assertThat(row.targetType()).isEqualTo("STORE");
        assertThat(row.change("tax_rate").path("from").asText()).isEqualTo("19");
        assertThat(row.change("tax_rate").path("to").asText()).isEqualTo("10");
        assertThat(row.json().path("changes").size()).isEqualTo(1);
    }

    @Test
    @DisplayName("La clave secreta de Stripe: vacía no deja nada; con valor, puesta y luego reemplazada, nunca el valor")
    void theStripeSecretIsRecordedWithoutItsValue() throws Exception {
        save(new Body());
        Body withSecret = new Body();
        // Encendido en los tres guardados: así el último, sin clave, no cambia nada (la guardada sigue sirviendo).
        withSecret.stripeEnabled = true;
        withSecret.stripeSecretKey = "sk_test_PRIMERA_CLAVE";
        save(withSecret);
        withSecret.stripeSecretKey = "sk_test_SEGUNDA_CLAVE";
        save(withSecret);
        withSecret.stripeSecretKey = "";
        save(withSecret);

        List<AuditRows.Row> rows = auditRows(store, "SETTINGS_STRIPE_UPDATE");
        assertThat(rows).hasSize(3);
        assertThat(rows.get(1).change("stripe_secret_key").asText()).isEqualTo("set");
        assertThat(rows.get(1).change("stripe_enabled").path("to").asText()).isEqualTo("true");
        assertThat(rows.get(2).change("stripe_secret_key").asText()).isEqualTo("replaced");
        assertThat(rows).allSatisfy(row -> assertThat(row.details()).doesNotContain("sk_test_", "pk_test_"));
    }

    @Test
    @DisplayName("Conectar el POS deja la empresa y que se pusieron el token y el secreto, sin sus valores")
    void connectingThePosIsRecordedWithoutSecrets() throws Exception {
        long company = 880_000_000L + nextSeq();
        Body withPos = new Body();
        withPos.posApiToken = "uvp_" + company + "_TokenDelPosNoDebeAparecer01";
        withPos.posWebhookSecret = "secreto-del-webhook-que-no-debe-aparecer";
        save(withPos);

        AuditRows.Row row = auditRow(store, "POS_CONNECTION_UPDATE");
        assertThat(row.targetType()).isEqualTo("POS_CONNECTION");
        assertThat(row.change("apiKey").asText()).isEqualTo("set");
        assertThat(row.change("webhookSecret").asText()).isEqualTo("set");
        assertThat(row.change("companyId").path("to").asLong()).isEqualTo(company);
        assertThat(row.json().path("connectionCreated").asBoolean()).isTrue();
        assertThat(row.details()).doesNotContain("TokenDelPos", "secreto-del-webhook");
    }

    private void save(Body body) throws Exception {
        mockMvc.perform(put("/api/admin/settings/general")
                        .header("Host", hostHeader(store))
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content(body.json()))
                .andExpect(status().isOk());
    }

    /** El formulario completo, como lo manda el panel; cada test cambia lo que le importa. */
    private static final class Body {
        String storeName = "Tienda";
        String taxRate = "19";
        String stripeSecretKey = "";
        Boolean stripeEnabled;
        String posApiToken = "";
        String posWebhookSecret = "";

        String json() {
            return """
                    {
                      "storeName":"%s","storeEmail":"tienda@test.local","storePhone":"","adminEmail":"admin@test.local",
                      "currency":"CLP","currencySymbol":"$","taxRate":"%s","pricesIncludeTax":true,
                      "shippingEnabled":true,"defaultShippingCost":"0","freeShippingEnabled":false,"freeShippingThreshold":"0",
                      "allowGuestCheckout":true,"requirePhone":false,"requireCompany":false,
                      "stripePublicKey":"pk_test_123","stripeSecretKey":"%s","stripeEnabled":%s,
                      "posApiUrl":"https://pos.test","posApiToken":"%s","posWebhookSecret":"%s","posSyncEnabled":%s,
                      "metaTitle":"","metaDescription":"","metaKeywords":"","facebookUrl":"","instagramUrl":"","twitterUrl":""
                    }
                    """.formatted(storeName, taxRate, stripeSecretKey,
                    stripeEnabled != null ? stripeEnabled : !stripeSecretKey.isBlank(), posApiToken,
                    posWebhookSecret, !posApiToken.isBlank());
        }
    }
}
