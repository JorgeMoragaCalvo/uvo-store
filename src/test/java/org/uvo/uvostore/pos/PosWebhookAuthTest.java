package org.uvo.uvostore.pos;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.uvo.uvostore.entity.pos.PosConnection;
import org.uvo.uvostore.entity.tenant.Store;
import org.uvo.uvostore.repository.PosConnectionRepository;
import org.uvo.uvostore.support.IntegrationTestSupport;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * B10. La superficie POS —un endpoint de sincronización y cuatro webhooks— no tenía ni un test, y es
 * la única entrada al sistema que se autentica con HMAC en vez de con JWT. Todo lo que la protege
 * vive en dos filtros que nadie estaba ejercitando: {@code PosWebhookAuthFilter} y
 * {@code PosApiKeyAuthFilter}.
 *
 * <p>Las rutas están en {@code SecurityConfig.PUBLIC_PATHS} (Spring Security no las mira), así que
 * esos filtros son literalmente lo único que hay entre internet y el stock de la tienda.
 */
class PosWebhookAuthTest extends IntegrationTestSupport {

    private static final String WEBHOOK_SECRET = "secreto-de-webhook-pos-de-prueba";
    private static final String API_KEY = "api-key-pos-de-prueba";

    @Autowired
    private PosConnectionRepository posConnectionRepository;

    // --- webhooks: /api/webhooks/pos/** ------------------------------------------------------------

    @Test
    @DisplayName("Sin las cabeceras de firma no se atiende")
    void aRequestWithNoSignatureHeadersIsRejected() throws Exception {
        PosConnection connection = createConnection(true);

        mockMvc.perform(post("/api/webhooks/pos/stock-updated")
                        .contentType("application/json")
                        .content(stockPayload(connection.getCompanyId())))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("MISSING_HEADERS"));
    }

    @Test
    @DisplayName("Con la firma correcta el webhook llega al controlador")
    void aCorrectlySignedWebhookIsAccepted() throws Exception {
        PosConnection connection = createConnection(true);
        String payload = stockPayload(connection.getCompanyId());
        String timestamp = String.valueOf(System.currentTimeMillis() / 1000);

        mockMvc.perform(post("/api/webhooks/pos/stock-updated")
                        .header("X-Signature", hmac(payload + timestamp, WEBHOOK_SECRET))
                        .header("X-Company-ID", String.valueOf(connection.getCompanyId()))
                        .header("X-Timestamp", timestamp)
                        .contentType("application/json")
                        .content(payload))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Una firma calculada con otro secreto no pasa")
    void aSignatureFromAnotherSecretIsRejected() throws Exception {
        PosConnection connection = createConnection(true);
        String payload = stockPayload(connection.getCompanyId());
        String timestamp = String.valueOf(System.currentTimeMillis() / 1000);

        mockMvc.perform(post("/api/webhooks/pos/stock-updated")
                        .header("X-Signature", hmac(payload + timestamp, "otro-secreto"))
                        .header("X-Company-ID", String.valueOf(connection.getCompanyId()))
                        .header("X-Timestamp", timestamp)
                        .contentType("application/json")
                        .content(payload))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("INVALID_SIGNATURE"));
    }

    @Test
    @DisplayName("El cuerpo va firmado: cambiarlo invalida la firma")
    void tamperingWithTheBodyInvalidatesTheSignature() throws Exception {
        PosConnection connection = createConnection(true);
        String signedPayload = stockPayload(connection.getCompanyId());
        String timestamp = String.valueOf(System.currentTimeMillis() / 1000);

        // Firma legítima, pero se envía otro stock: es el ataque que la firma tiene que detener.
        String tampered = signedPayload.replace("\"newStock\":7", "\"newStock\":99999");

        mockMvc.perform(post("/api/webhooks/pos/stock-updated")
                        .header("X-Signature", hmac(signedPayload + timestamp, WEBHOOK_SECRET))
                        .header("X-Company-ID", String.valueOf(connection.getCompanyId()))
                        .header("X-Timestamp", timestamp)
                        .contentType("application/json")
                        .content(tampered))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("INVALID_SIGNATURE"));
    }

    @Test
    @DisplayName("Una firma vieja no sirve para reenviar la misma petición")
    void anExpiredTimestampIsRejected() throws Exception {
        PosConnection connection = createConnection(true);
        String payload = stockPayload(connection.getCompanyId());
        // La ventana del filtro es de 300s; 10 minutos atrás queda fuera con margen.
        String oldTimestamp = String.valueOf(System.currentTimeMillis() / 1000 - 600);

        mockMvc.perform(post("/api/webhooks/pos/stock-updated")
                        .header("X-Signature", hmac(payload + oldTimestamp, WEBHOOK_SECRET))
                        .header("X-Company-ID", String.valueOf(connection.getCompanyId()))
                        .header("X-Timestamp", oldTimestamp)
                        .contentType("application/json")
                        .content(payload))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("EXPIRED_WEBHOOK"));
    }

    @Test
    @DisplayName("Una conexión desactivada deja de ser una puerta abierta")
    void anInactiveConnectionIsRejected() throws Exception {
        PosConnection connection = createConnection(false);
        String payload = stockPayload(connection.getCompanyId());
        String timestamp = String.valueOf(System.currentTimeMillis() / 1000);

        mockMvc.perform(post("/api/webhooks/pos/stock-updated")
                        .header("X-Signature", hmac(payload + timestamp, WEBHOOK_SECRET))
                        .header("X-Company-ID", String.valueOf(connection.getCompanyId()))
                        .header("X-Timestamp", timestamp)
                        .contentType("application/json")
                        .content(payload))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("POS_CONNECTION_NOT_FOUND"));
    }

    @Test
    @DisplayName("Un company id que no existe no revela nada distinto")
    void anUnknownCompanyIdIsRejected() throws Exception {
        createConnection(true);
        String payload = stockPayload(999_999_999L);
        String timestamp = String.valueOf(System.currentTimeMillis() / 1000);

        mockMvc.perform(post("/api/webhooks/pos/stock-updated")
                        .header("X-Signature", hmac(payload + timestamp, WEBHOOK_SECRET))
                        .header("X-Company-ID", "999999999")
                        .header("X-Timestamp", timestamp)
                        .contentType("application/json")
                        .content(payload))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("POS_CONNECTION_NOT_FOUND"));
    }

    // --- sincronización: /api/sync/** --------------------------------------------------------------

    @Test
    @DisplayName("Sin API key no se sincroniza nada")
    void syncWithoutAnApiKeyIsRejected() throws Exception {
        PosConnection connection = createConnection(true);

        mockMvc.perform(post("/api/sync/product")
                        .header("X-Company-ID", String.valueOf(connection.getCompanyId()))
                        .contentType("application/json")
                        .content(syncPayload(connection.getCompanyId())))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("MISSING_API_KEY"));
    }

    @Test
    @DisplayName("Con una API key equivocada tampoco")
    void syncWithAWrongApiKeyIsRejected() throws Exception {
        PosConnection connection = createConnection(true);

        mockMvc.perform(post("/api/sync/product")
                        .header("Authorization", "Bearer api-key-que-no-es")
                        .header("X-Company-ID", String.valueOf(connection.getCompanyId()))
                        .contentType("application/json")
                        .content(syncPayload(connection.getCompanyId())))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("INVALID_API_KEY"));
    }

    @Test
    @DisplayName("Con la API key correcta el producto se sincroniza")
    void syncWithTheRightApiKeyWorks() throws Exception {
        PosConnection connection = createConnection(true);

        mockMvc.perform(post("/api/sync/product")
                        .header("Authorization", "Bearer " + API_KEY)
                        .header("X-Company-ID", String.valueOf(connection.getCompanyId()))
                        .contentType("application/json")
                        .content(syncPayload(connection.getCompanyId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    // --- fixtures ----------------------------------------------------------------------------------

    /**
     * La conexión guarda apiKey y webhookSecret con {@code EncryptedStringConverter}, así que estos
     * tests recorren de paso el cifrado en reposo de extremo a extremo: si el converter dejara de
     * descifrar, el filtro compararía contra basura y todo lo de arriba se caería.
     */
    private PosConnection createConnection(boolean active) {
        Store store = createStore("pos-" + (active ? "on" : "off"));
        PosConnection connection = new PosConnection();
        connection.setStore(store);
        connection.setCompanyName("Empresa POS");
        connection.setCompanyId(nextSeq());
        connection.setApiUrl("https://pos.test.local/api");
        connection.setApiKey(API_KEY);
        connection.setWebhookSecret(WEBHOOK_SECRET);
        connection.setActive(active);
        return posConnectionRepository.save(connection);
    }

    private String stockPayload(long companyId) {
        return """
                {"event":"stock.updated","productId":1,"sku":"SKU-1","companyId":%d,\
                "warehouseId":1,"oldStock":10,"newStock":7,"stockWeb":7}"""
                .formatted(companyId);
    }

    private String syncPayload(long companyId) {
        return """
                {"companyId":%d,"externalId":%d,"sku":"POS-SKU-%d","name":"Producto POS",\
                "price":1990,"stock":5,"active":true}"""
                .formatted(companyId, nextSeq(), nextSeq());
    }

    private String hmac(String data, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
