package org.uvo.uvostore.pos;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.uvo.uvostore.entity.pos.PosConnection;
import org.uvo.uvostore.entity.pos.PosConnectionKey;
import org.uvo.uvostore.entity.pos.PosWebhookReceipt;
import org.uvo.uvostore.entity.tenant.Store;
import org.uvo.uvostore.repository.PosConnectionKeyRepository;
import org.uvo.uvostore.repository.PosConnectionRepository;
import org.uvo.uvostore.repository.PosWebhookReceiptRepository;
import org.uvo.uvostore.repository.SyncWebHookLogRepository;
import org.uvo.uvostore.security.PosWebhookReplayGuard;
import org.uvo.uvostore.security.UvoHmac;
import org.uvo.uvostore.support.IntegrationTestSupport;
import org.uvo.uvostore.support.PosSigning;
import org.uvo.uvostore.support.WithPosIntegration;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * B10 e IMP-08. La superficie de entrada del POS —la carga y los cuatro avisos— se autentica con la firma UVO-HMAC del
 * contrato (§3), en {@code PosSignatureAuthFilter}. Las rutas están en {@code SecurityConfig.PUBLIC_PATHS}, así que
 * ese filtro es lo único que hay entre internet y el stock de la tienda.
 */
@WithPosIntegration
class PosWebhookAuthTest extends IntegrationTestSupport {

    private static final String STOCK = "/api/webhooks/pos/stock-updated";

    @Autowired
    private PosConnectionRepository posConnectionRepository;
    @Autowired
    private PosConnectionKeyRepository keyRepository;
    @Autowired
    private SyncWebHookLogRepository webhookLogRepository;
    @Autowired
    private PosWebhookReceiptRepository receiptRepository;
    @Autowired
    private PosWebhookReplayGuard replayGuard;

    @Test
    @DisplayName("Sin las cabeceras de firma no se atiende")
    void aRequestWithNoSignatureHeadersIsRejected() throws Exception {
        PosConnection connection = createConnection(true);

        mockMvc.perform(post(STOCK).header(UvoHmac.HEADER_CONTRACT, "1")
                        .contentType("application/json").content(stockPayload(connection.getCompanyId(), 1)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
    }

    @Test
    @DisplayName("Con la firma correcta el aviso llega al controlador")
    void aCorrectlySignedWebhookIsAccepted() throws Exception {
        PosConnection connection = createConnection(true);

        mockMvc.perform(PosSigning.signedPost(STOCK, stockPayload(connection.getCompanyId(), 1), connection))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    @DisplayName("Sin X-Uvo-Contract, o con una versión que no se conoce: 400 UNSUPPORTED_CONTRACT")
    void anUnknownContractVersionIs400() throws Exception {
        PosConnection connection = createConnection(true);

        mockMvc.perform(PosSigning.signedPost(STOCK, stockPayload(connection.getCompanyId(), 1),
                        String.valueOf(connection.getCompanyId()), PosSigning.inboundKeyId(connection),
                        PosSigning.inboundSecret(connection), now(), UvoHmac.newNonce(), "2"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("UNSUPPORTED_CONTRACT"));
    }

    @Test
    @DisplayName("Una petición con query string se rechaza: ningún endpoint del contrato la usa")
    void aQueryStringIs400() throws Exception {
        PosConnection connection = createConnection(true);
        String body = stockPayload(connection.getCompanyId(), 1);

        mockMvc.perform(PosSigning.signedPost(STOCK, body, connection).queryParam("x", "1"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Una firma calculada con otro secreto no pasa")
    void aSignatureFromAnotherSecretIsRejected() throws Exception {
        PosConnection connection = createConnection(true);

        mockMvc.perform(PosSigning.signedPost(STOCK, stockPayload(connection.getCompanyId(), 1),
                        String.valueOf(connection.getCompanyId()), PosSigning.inboundKeyId(connection),
                        PosSigning.secret("otro"), now(), UvoHmac.newNonce()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
    }

    @Test
    @DisplayName("La llave tienda→POS no sirve para firmar como el POS")
    void theOutboundKeyCannotSignInbound() throws Exception {
        PosConnection connection = createConnection(true);

        mockMvc.perform(PosSigning.signedPost(STOCK, stockPayload(connection.getCompanyId(), 1),
                        String.valueOf(connection.getCompanyId()), PosSigning.outboundKeyId(connection),
                        PosSigning.outboundSecret(connection), now(), UvoHmac.newNonce()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("El cuerpo va firmado: cambiarlo invalida la firma")
    void tamperingWithTheBodyInvalidatesTheSignature() throws Exception {
        PosConnection connection = createConnection(true);
        String signed = stockPayload(connection.getCompanyId(), 1);
        var request = PosSigning.signedPost(STOCK, signed, connection)
                .content(signed.replace("\"stockWeb\":7", "\"stockWeb\":99999"));

        mockMvc.perform(request).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Un timestamp a más de 300 s no sirve")
    void anExpiredTimestampIsRejected() throws Exception {
        PosConnection connection = createConnection(true);

        mockMvc.perform(PosSigning.signedPost(STOCK, stockPayload(connection.getCompanyId(), 1),
                        String.valueOf(connection.getCompanyId()), PosSigning.inboundKeyId(connection),
                        PosSigning.inboundSecret(connection), now() - 600, UvoHmac.newNonce()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Un nonce que no tiene el formato se rechaza")
    void aMalformedNonceIsRejected() throws Exception {
        PosConnection connection = createConnection(true);

        mockMvc.perform(PosSigning.signedPost(STOCK, stockPayload(connection.getCompanyId(), 1),
                        String.valueOf(connection.getCompanyId()), PosSigning.inboundKeyId(connection),
                        PosSigning.inboundSecret(connection), now(), "corto"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Una conexión desactivada deja de ser una puerta abierta")
    void anInactiveConnectionIsRejected() throws Exception {
        PosConnection connection = createConnection(false);

        mockMvc.perform(PosSigning.signedPost(STOCK, stockPayload(connection.getCompanyId(), 1), connection))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Un key_id que no existe responde igual que una firma mala")
    void anUnknownKeyIsRejected() throws Exception {
        PosConnection connection = createConnection(true);

        mockMvc.perform(PosSigning.signedPost(STOCK, stockPayload(connection.getCompanyId(), 1),
                        String.valueOf(connection.getCompanyId()), "llave-que-no-existe",
                        PosSigning.inboundSecret(connection), now(), UvoHmac.newNonce()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
    }

    @Test
    @DisplayName("X-Company-ID distinto de la empresa de la llave: 403 FORBIDDEN_COMPANY")
    void aCompanyHeaderOfAnotherCompanyIs403() throws Exception {
        PosConnection connection = createConnection(true);

        mockMvc.perform(PosSigning.signedPost(STOCK, stockPayload(connection.getCompanyId(), 1),
                        String.valueOf(connection.getCompanyId() + 1), PosSigning.inboundKeyId(connection),
                        PosSigning.inboundSecret(connection), now(), UvoHmac.newNonce()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN_COMPANY"));
    }

    @Test
    @DisplayName("Una llave revocada da 401 en el acto")
    void aRevokedKeyIsRejected() throws Exception {
        PosConnection connection = createConnection(true);
        keyRepository.findWithConnection(PosConnectionKey.Direction.INBOUND, PosSigning.inboundKeyId(connection))
                .ifPresent(key -> {
                    key.setRevokedAt(Instant.now());
                    keyRepository.save(key);
                });

        mockMvc.perform(PosSigning.signedPost(STOCK, stockPayload(connection.getCompanyId(), 1), connection))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Durante la rotación valen la llave nueva y la anterior; una retirada ya no")
    void duringRotationBothKeysWork() throws Exception {
        PosConnection connection = createConnection(true);
        String oldKeyId = PosSigning.inboundKeyId(connection);
        keyRepository.findWithConnection(PosConnectionKey.Direction.INBOUND, oldKeyId).ifPresent(key -> {
            key.setRetiresAt(Instant.now().plus(Duration.ofDays(7)));
            keyRepository.save(key);
        });
        String newSecret = PosSigning.secret("rotada-" + connection.getCompanyId());
        keyRepository.save(PosSigning.key(connection, PosConnectionKey.Direction.INBOUND, "rotada-" + connection.getCompanyId(),
                newSecret));

        mockMvc.perform(PosSigning.signedPost(STOCK, stockPayload(connection.getCompanyId(), 1), connection))
                .andExpect(status().isOk());
        mockMvc.perform(PosSigning.signedPost(STOCK, stockPayload(connection.getCompanyId(), 2),
                        String.valueOf(connection.getCompanyId()), "rotada-" + connection.getCompanyId(), newSecret,
                        now(), UvoHmac.newNonce()))
                .andExpect(status().isOk());

        keyRepository.findWithConnection(PosConnectionKey.Direction.INBOUND, oldKeyId).ifPresent(key -> {
            key.setRetiresAt(Instant.now().minusSeconds(1));
            keyRepository.save(key);
        });
        mockMvc.perform(PosSigning.signedPost(STOCK, stockPayload(connection.getCompanyId(), 3), connection))
                .andExpect(status().isUnauthorized());
    }

    // --- antirreplay por nonce (§3, regla 3) -------------------------------------------------------

    @Test
    @DisplayName("El mismo nonce dos veces: la segunda se rechaza y no se procesa")
    void aReplayedNonceIsRejected() throws Exception {
        PosConnection connection = createConnection(true);
        String body = stockPayload(connection.getCompanyId(), 1);
        String nonce = UvoHmac.newNonce();
        long timestamp = now();

        mockMvc.perform(signed(connection, body, timestamp, nonce)).andExpect(status().isOk());
        mockMvc.perform(signed(connection, body, timestamp, nonce)).andExpect(status().isUnauthorized());

        assertThat(webhookLogRepository.findTop50ByCompanyIdOrderByCreatedAtDesc(connection.getCompanyId()))
                .as("el reenvío no se procesa, así que no hay segundo registro")
                .hasSize(1);
    }

    @Test
    @DisplayName("Una firma inválida no consume el nonce: no puede bloquear la petición auténtica")
    void aRejectedSignatureDoesNotBurnTheNonce() throws Exception {
        PosConnection connection = createConnection(true);
        String body = stockPayload(connection.getCompanyId(), 1);
        String nonce = UvoHmac.newNonce();
        long timestamp = now();

        for (int attempt = 0; attempt < 3; attempt++) {
            mockMvc.perform(PosSigning.signedPost(STOCK, body, String.valueOf(connection.getCompanyId()),
                            PosSigning.inboundKeyId(connection), PosSigning.secret("otro"), timestamp, nonce))
                    .andExpect(status().isUnauthorized());
        }
        mockMvc.perform(signed(connection, body, timestamp, nonce)).andExpect(status().isOk());
    }

    @Test
    @DisplayName("Lo atendido antes de un reinicio sigue sin poder repetirse")
    void aReplayIsRejectedEvenAfterARestart() throws Exception {
        PosConnection connection = createConnection(true);
        String nonce = UvoHmac.newNonce();
        receiptRepository.save(receipt(PosSigning.inboundKeyId(connection), nonce, Instant.now()));

        mockMvc.perform(signed(connection, stockPayload(connection.getCompanyId(), 1), now(), nonce))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("La purga guarda cada nonce mientras pueda seguir fresco, y no más")
    void receiptsAreKeptWhileTheyCanStillBeFresh() {
        String keyId = "purga-" + nextSeq();
        PosWebhookReceipt stillFresh = receiptRepository.save(
                receipt(keyId, UvoHmac.newNonce(), Instant.now().minus(Duration.ofMinutes(8))));
        PosWebhookReceipt expired = receiptRepository.save(
                receipt(keyId, UvoHmac.newNonce(), Instant.now().minus(Duration.ofMinutes(12))));

        replayGuard.purgeExpired();

        assertThat(receiptRepository.existsById(stillFresh.getId())).isTrue();
        assertThat(receiptRepository.existsById(expired.getId())).isFalse();
    }

    // --- la versión es obligatoria (§5) -------------------------------------------------------------

    @Test
    @DisplayName("Un aviso de stock o de producto sin versión se rechaza: no habría cómo ordenarlo")
    void eventsWithoutAVersionAre400() throws Exception {
        PosConnection connection = createConnection(true);
        String stock = stockPayload(connection.getCompanyId(), 1).replace(",\"version\":1", "");
        String product = """
                {"event":"product.updated","productId":1,"sku":"SKU-1","companyId":%d,\
                "changedFields":["price"],"data":{"price":1990}}""".formatted(connection.getCompanyId());

        mockMvc.perform(PosSigning.signedPost(STOCK, stock, connection)).andExpect(status().isBadRequest());
        mockMvc.perform(PosSigning.signedPost("/api/webhooks/pos/product-updated", product, connection))
                .andExpect(status().isBadRequest());
        mockMvc.perform(PosSigning.signedPost("/api/sync/product",
                        syncPayload(connection.getCompanyId()).replace(",\"version\":1", ""), connection))
                .andExpect(status().isBadRequest());
    }

    // --- carga: /api/sync/product -------------------------------------------------------------------

    @Test
    @DisplayName("La carga va firmada como los avisos: sin firma no entra")
    void syncWithoutASignatureIsRejected() throws Exception {
        PosConnection connection = createConnection(true);

        mockMvc.perform(post("/api/sync/product").header(UvoHmac.HEADER_CONTRACT, "1")
                        .header("X-Company-ID", String.valueOf(connection.getCompanyId()))
                        .contentType("application/json").content(syncPayload(connection.getCompanyId())))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Con la firma correcta el producto se carga")
    void aSignedSyncWorks() throws Exception {
        PosConnection connection = createConnection(true);

        mockMvc.perform(PosSigning.signedPost("/api/sync/product", syncPayload(connection.getCompanyId()), connection))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.applied").value(true));
    }

    @Test
    @DisplayName("Un companyId en el cuerpo distinto del autenticado se rechaza")
    void aBodyCompanyIdThatDiffersFromTheAuthenticatedOneIsRejected() throws Exception {
        PosConnection connection = createConnection(true);

        // F01. La firma es correcta y la cabecera también: lo único mal es el companyId del cuerpo.
        mockMvc.perform(PosSigning.signedPost("/api/sync/product", syncPayload(connection.getCompanyId() + 1),
                        connection))
                .andExpect(status().isForbidden());
        mockMvc.perform(PosSigning.signedPost(STOCK, stockPayload(connection.getCompanyId() + 1, 1), connection))
                .andExpect(status().isForbidden());
    }

    // --- fixtures ----------------------------------------------------------------------------------

    /**
     * Las llaves se guardan con {@code EncryptedStringConverter}, así que estas pruebas recorren de paso el cifrado en
     * reposo de extremo a extremo: si dejara de descifrar, el filtro verificaría contra basura.
     */
    private PosConnection createConnection(boolean active) {
        Store store = createStore("pos-" + (active ? "on" : "off"));
        PosConnection connection = new PosConnection();
        connection.setStore(store);
        connection.setCompanyName("Empresa POS");
        connection.setCompanyId(nextSeq());
        connection.setApiUrl("https://pos.test.local/api");
        connection.setActive(active);
        return PosSigning.installKeys(keyRepository, posConnectionRepository.save(connection));
    }

    private org.springframework.test.web.servlet.RequestBuilder signed(PosConnection connection, String body,
                                                                      long timestamp, String nonce) {
        return PosSigning.signedPost(STOCK, body, String.valueOf(connection.getCompanyId()),
                PosSigning.inboundKeyId(connection), PosSigning.inboundSecret(connection), timestamp, nonce);
    }

    private static long now() {
        return System.currentTimeMillis() / 1000;
    }

    private static String stockPayload(long companyId, long version) {
        return """
                {"event":"stock.updated","productId":1,"sku":"SKU-1","companyId":%d,\
                "warehouseId":1,"version":%d,"oldStock":10,"newStock":7,"stockWeb":7}"""
                .formatted(companyId, version);
    }

    private String syncPayload(long companyId) {
        return """
                {"companyId":%d,"externalId":%d,"sku":"POS-SKU-%d","name":"Producto POS",\
                "price":1990,"stock":5,"active":true,"version":1}"""
                .formatted(companyId, nextSeq(), nextSeq());
    }

    private static PosWebhookReceipt receipt(String keyId, String nonce, Instant receivedAt) {
        PosWebhookReceipt receipt = new PosWebhookReceipt();
        receipt.setKeyId(keyId);
        receipt.setNonce(nonce);
        receipt.setReceivedAt(receivedAt);
        return receipt;
    }
}
