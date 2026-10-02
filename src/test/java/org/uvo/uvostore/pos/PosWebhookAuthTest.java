package org.uvo.uvostore.pos;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.uvo.uvostore.entity.pos.PosConnection;
import org.uvo.uvostore.entity.tenant.Store;
import org.uvo.uvostore.repository.PosConnectionRepository;
import org.uvo.uvostore.repository.SyncWebHookLogRepository;
import org.uvo.uvostore.support.IntegrationTestSupport;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;
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
    @Autowired
    private SyncWebHookLogRepository webhookLogRepository;

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

    // --- F21: repetición dentro de la ventana de frescura ------------------------------------------

    @Test
    @DisplayName("El mismo webhook dos veces: la segunda se rechaza como duplicado")
    void aReplayedWebhookIsRejected() throws Exception {
        PosConnection connection = createConnection(true);
        String payload = stockPayload(connection.getCompanyId());
        String timestamp = String.valueOf(System.currentTimeMillis() / 1000);
        String signature = hmac(payload + timestamp, WEBHOOK_SECRET);

        mockMvc.perform(signedWebhook(connection, payload, timestamp, signature))
                .andExpect(status().isOk());

        // Exactamente la misma petición: la firma sigue siendo válida porque el cuerpo y el timestamp no
        // han cambiado. Eso es todo lo que necesita quien la haya capturado.
        mockMvc.perform(signedWebhook(connection, payload, timestamp, signature))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("REPLAYED_WEBHOOK"));
    }

    @Test
    @DisplayName("Y el reenvío no llega a procesarse: no deja una segunda fila de log")
    void aReplayedWebhookIsNotProcessedAtAll() throws Exception {
        PosConnection connection = createConnection(true);
        String payload = stockPayload(connection.getCompanyId());
        String timestamp = String.valueOf(System.currentTimeMillis() / 1000);
        String signature = hmac(payload + timestamp, WEBHOOK_SECRET);

        mockMvc.perform(signedWebhook(connection, payload, timestamp, signature)).andExpect(status().isOk());
        mockMvc.perform(signedWebhook(connection, payload, timestamp, signature));

        // Rechazar en el filtro es lo que evita el efecto de verdad molesto: el reenvío entraba al
        // servicio, el UPDATE condicional de F13 no encajaba —el stock ya era el nuevo— y se registraba
        // una divergencia con su aviso a Sentry que no correspondía a ninguna divergencia real.
        assertThat(webhookLogRepository.findTop50ByCompanyIdOrderByCreatedAtDesc(connection.getCompanyId()))
                .as("el reenvío no se procesa, así que no hay segundo registro")
                .hasSize(1);
    }

    @Test
    @DisplayName("Dos eventos legítimos distintos del mismo comercio pasan los dos")
    void twoDistinctEventsBothPass() throws Exception {
        PosConnection connection = createConnection(true);
        String timestamp = String.valueOf(System.currentTimeMillis() / 1000);

        // El guarda no puede degenerar en "un webhook por ventana": lo que distingue a dos eventos es el
        // cuerpo, y con cuerpos distintos la firma también es distinta.
        String first = stockPayload(connection.getCompanyId());
        String second = """
                {"event":"stock.updated","productId":2,"sku":"SKU-2","companyId":%d,\
                "warehouseId":1,"oldStock":4,"newStock":3,"stockWeb":3}"""
                .formatted(connection.getCompanyId());

        mockMvc.perform(signedWebhook(connection, first, timestamp, hmac(first + timestamp, WEBHOOK_SECRET)))
                .andExpect(status().isOk());
        mockMvc.perform(signedWebhook(connection, second, timestamp, hmac(second + timestamp, WEBHOOK_SECRET)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Una firma inválida no se recuerda: no puede bloquear al webhook auténtico")
    void aRejectedSignatureDoesNotBlockTheGenuineRequest() throws Exception {
        PosConnection connection = createConnection(true);
        String payload = stockPayload(connection.getCompanyId());
        String timestamp = String.valueOf(System.currentTimeMillis() / 1000);
        String forged = hmac(payload + timestamp, "otro-secreto");

        // Tres intentos con una firma que no vale. Si el registro fuera antes de verificar el HMAC,
        // cualquiera podría quemar la firma auténtica —o llenar la caché con basura— sin conocer el
        // secreto.
        for (int attempt = 0; attempt < 3; attempt++) {
            mockMvc.perform(signedWebhook(connection, payload, timestamp, forged))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.error").value("INVALID_SIGNATURE"));
        }

        mockMvc.perform(signedWebhook(connection, payload, timestamp, hmac(payload + timestamp, WEBHOOK_SECRET)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Fuera de la ventana el motivo sigue siendo la expiración, no el duplicado")
    void anExpiredReplayIsStillReportedAsExpired() throws Exception {
        PosConnection connection = createConnection(true);
        String payload = stockPayload(connection.getCompanyId());
        String timestamp = String.valueOf(System.currentTimeMillis() / 1000);
        String signature = hmac(payload + timestamp, WEBHOOK_SECRET);

        mockMvc.perform(signedWebhook(connection, payload, timestamp, signature)).andExpect(status().isOk());

        // La frescura se comprueba antes que el duplicado, y así tiene que seguir: los dos rechazos dicen
        // cosas distintas y el operador necesita distinguirlos en el log.
        String oldTimestamp = String.valueOf(System.currentTimeMillis() / 1000 - 600);
        mockMvc.perform(signedWebhook(connection, payload, oldTimestamp, hmac(payload + oldTimestamp, WEBHOOK_SECRET)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("EXPIRED_WEBHOOK"));
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

    @Test
    @DisplayName("Un companyId en el cuerpo distinto del autenticado se rechaza")
    void aBodyCompanyIdThatDiffersFromTheAuthenticatedOneIsRejected() throws Exception {
        PosConnection connection = createConnection(true);

        // F01. La firma es correcta y la cabecera también: lo único mal es el companyId del cuerpo.
        // Se rechaza en vez de ignorarlo en silencio — si UvoPOS empezara a mandar otro id, un 403 se
        // ve y un valor descartado no. El escenario completo (dos tiendas) vive en
        // multitenancy/PosCrossTenantWriteTest.
        String payload = stockPayload(connection.getCompanyId() + 1);
        String timestamp = String.valueOf(System.currentTimeMillis() / 1000);

        mockMvc.perform(post("/api/sync/product")
                        .header("Authorization", "Bearer " + API_KEY)
                        .header("X-Company-ID", String.valueOf(connection.getCompanyId()))
                        .contentType("application/json")
                        .content(syncPayload(connection.getCompanyId() + 1)))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/webhooks/pos/stock-updated")
                        .header("X-Signature", hmac(payload + timestamp, WEBHOOK_SECRET))
                        .header("X-Company-ID", String.valueOf(connection.getCompanyId()))
                        .header("X-Timestamp", timestamp)
                        .contentType("application/json")
                        .content(payload))
                .andExpect(status().isForbidden());
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

    /**
     * F21. La petición firmada, para poder mandar dos veces exactamente la misma — que es todo el
     * escenario del hallazgo.
     */
    private org.springframework.test.web.servlet.RequestBuilder signedWebhook(
            PosConnection connection, String payload, String timestamp, String signature) {
        return post("/api/webhooks/pos/stock-updated")
                .header("X-Signature", signature)
                .header("X-Company-ID", String.valueOf(connection.getCompanyId()))
                .header("X-Timestamp", timestamp)
                .contentType("application/json")
                .content(payload);
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
