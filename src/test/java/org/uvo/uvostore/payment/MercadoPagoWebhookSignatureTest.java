package org.uvo.uvostore.payment;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.uvo.uvostore.entity.security.User;
import org.uvo.uvostore.entity.tenant.Store;
import org.uvo.uvostore.service.payment.MercadoPagoWebhookSignature;
import org.uvo.uvostore.support.IntegrationTestSupport;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * M3. /api/v1/mercadopago/webhook is public and unauthenticated, and each accepted hit costs the
 * merchant an outbound API call — a free lever against their quota. The handler already refused to
 * trust the body (it re-queries MercadoPago), so a payment couldn't be forged, but nothing stopped
 * the requests.
 */
class MercadoPagoWebhookSignatureTest extends IntegrationTestSupport {

    private static final String SECRET = "secreto-de-webhook-de-prueba";

    private final MercadoPagoWebhookSignature signature = new MercadoPagoWebhookSignature();

    // --- la verificación en sí (unitaria, sin contexto) -----------------------------------------

    @Test
    @DisplayName("Acepta una firma calculada con el secreto correcto")
    void acceptsAWellFormedSignature() {
        String header = "ts=1700000000000,v1=" + hmac("id:123;request-id:req-1;ts:1700000000000;", SECRET);

        assertThat(signature.isValid(header, "req-1", "123", SECRET)).isTrue();
    }

    @Test
    @DisplayName("Rechaza una firma calculada con otro secreto")
    void rejectsASignatureFromAnotherSecret() {
        String header = "ts=1700000000000,v1=" + hmac("id:123;request-id:req-1;ts:1700000000000;", "otro-secreto");

        assertThat(signature.isValid(header, "req-1", "123", SECRET)).isFalse();
    }

    @Test
    @DisplayName("Rechaza si cambia cualquier parte del manifiesto")
    void rejectsWhenTheManifestDoesNotMatch() {
        String header = "ts=1700000000000,v1=" + hmac("id:123;request-id:req-1;ts:1700000000000;", SECRET);

        // Otro pago: la firma era para el 123.
        assertThat(signature.isValid(header, "req-1", "999", SECRET)).isFalse();
        // Otro request-id.
        assertThat(signature.isValid(header, "req-2", "123", SECRET)).isFalse();
    }

    @Test
    @DisplayName("Rechaza cabeceras ausentes o mal formadas, y un secreto vacío")
    void rejectsMissingOrMalformedInput() {
        assertThat(signature.isValid(null, "req-1", "123", SECRET)).isFalse();
        assertThat(signature.isValid("", "req-1", "123", SECRET)).isFalse();
        assertThat(signature.isValid("basura", "req-1", "123", SECRET)).isFalse();
        assertThat(signature.isValid("ts=1700000000000", "req-1", "123", SECRET)).isFalse();
        assertThat(signature.isValid("ts=1,v1=abc", "req-1", "123", "")).isFalse();
    }

    // --- el endpoint ------------------------------------------------------------------------------

    @Test
    @DisplayName("Una notificación sin firma se rechaza antes de consultar la API de MercadoPago")
    void anUnsignedNotificationIsRejected() throws Exception {
        Store store = createStore("mp-hook");

        mockMvc.perform(post("/api/v1/mercadopago/webhook")
                        .header("Host", hostHeader(store))
                        .contentType("application/json")
                        .content("{\"type\":\"payment\",\"data\":{\"id\":\"123\"}}"))
                .andExpect(status().isUnauthorized());
    }

    // --- la configuración ------------------------------------------------------------------------

    @Test
    @DisplayName("No se puede activar MercadoPago sin el secreto de webhook")
    void enablingMercadoPagoWithoutTheSecretIsRefused() throws Exception {
        Store store = createStore("mp-config");
        User admin = createAdmin(store, "mp-config");
        String token = loginAdmin(store, admin);

        mockMvc.perform(put("/api/admin/payment-gateways/MERCADOPAGO")
                        .header("Host", hostHeader(store))
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content("{\"enabled\":true,\"credentials\":{\"accessToken\":\"APP-123\",\"publicKey\":\"PUB-123\"}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", org.hamcrest.Matchers.containsString("secreto de webhook")));
    }

    @Test
    @DisplayName("Con el secreto sí se puede activar")
    void enablingWithTheSecretWorks() throws Exception {
        Store store = createStore("mp-config-ok");
        User admin = createAdmin(store, "mp-config-ok");
        String token = loginAdmin(store, admin);

        mockMvc.perform(put("/api/admin/payment-gateways/MERCADOPAGO")
                        .header("Host", hostHeader(store))
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content("{\"enabled\":true,\"credentials\":{\"accessToken\":\"APP-123\",\"webhookSecret\":\"" + SECRET + "\"}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.credentialsSet.webhookSecret").value(true));
    }

    @Test
    @DisplayName("Desactivada no se exige el secreto: nadie va a enviarle webhooks")
    void aDisabledGatewayDoesNotRequireTheSecret() throws Exception {
        Store store = createStore("mp-config-off");
        User admin = createAdmin(store, "mp-config-off");
        String token = loginAdmin(store, admin);

        mockMvc.perform(put("/api/admin/payment-gateways/MERCADOPAGO")
                        .header("Host", hostHeader(store))
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content("{\"enabled\":false,\"credentials\":{\"accessToken\":\"APP-123\"}}"))
                .andExpect(status().isOk());
    }

    private String hmac(String manifest, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(manifest.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
