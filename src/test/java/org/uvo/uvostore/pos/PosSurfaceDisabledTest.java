package org.uvo.uvostore.pos;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.uvo.uvostore.entity.pos.PosConnection;
import org.uvo.uvostore.entity.tenant.Store;
import org.uvo.uvostore.repository.PosConnectionRepository;
import org.uvo.uvostore.support.IntegrationTestSupport;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * PROD-12. Con la integración POS apagada, su superficie de entrada no se sirve.
 *
 * <p><b>Sin {@code @WithPosIntegration} a propósito</b>: corre con el valor que se despacha, mientras
 * {@code PosWebhookAuthTest}, {@code PosStockRaceTest} y {@code PosCrossTenantWriteTest} lo encienden en
 * el suyo. Así «la integración está apagada» depende de un test y no de un comentario.
 *
 * <p>Lo que importa es que el 404 llegue <b>con credenciales buenas</b>: una conexión activa, una API key
 * correcta y una firma HMAC bien calculada. Si solo se comprobara sin credenciales, el test pasaría igual
 * con la puerta quitada —el filtro de firma ya rechaza a un desconocido— y no acreditaría nada.
 */
class PosSurfaceDisabledTest extends IntegrationTestSupport {

    @Autowired
    private PosConnectionRepository posConnectionRepository;

    private static final String SECRET = "un-secreto-de-webhook-de-prueba";

    @Test
    @DisplayName("Un webhook correctamente firmado por una conexión activa responde 404")
    void aValidWebhookIsGone() throws Exception {
        Store store = createStore("pos-off-webhook");
        PosConnection connection = activeConnection(store, 4242L);
        String body = "{\"company_id\":" + connection.getCompanyId() + ",\"product_id\":1,"
                + "\"old_stock\":5,\"new_stock\":4}";
        String timestamp = String.valueOf(System.currentTimeMillis() / 1000);

        expectGone(post("/api/webhooks/pos/stock-updated")
                .header("Host", hostHeader(store))
                .header("X-Company-ID", String.valueOf(connection.getCompanyId()))
                .header("X-Timestamp", timestamp)
                .header("X-Signature", sign(body + timestamp))
                .contentType("application/json")
                .content(body));
    }

    @Test
    @DisplayName("La sincronización de productos con la API key correcta responde 404")
    void theSyncSurfaceIsGone() throws Exception {
        Store store = createStore("pos-off-sync");
        PosConnection connection = activeConnection(store, 4343L);

        expectGone(post("/api/sync/product")
                .header("Host", hostHeader(store))
                .header("Authorization", "Bearer " + connection.getApiKey())
                .contentType("application/json")
                .content("{\"company_id\":" + connection.getCompanyId() + ",\"name\":\"Algo\",\"price\":1000}"));
    }

    @Test
    @DisplayName("El 404 llega sin credencial, y no es el 401 de «vuelve a entrar»")
    void theGateAnswersBeforeSecurity() throws Exception {
        Store store = createStore("pos-off-anon");

        mockMvc.perform(post("/api/sync/product")
                        .header("Host", hostHeader(store))
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isNotFound())
                // Si el filtro corriera después de la cadena de seguridad, estas rutas responderían el
                // 401 de los filtros de API key del POS. El contraste con PosWebhookAuthTest —que espera
                // exactamente ese 401 con la integración encendida— es lo que prueba el orden.
                .andExpect(header().doesNotExist("WWW-Authenticate"))
                .andExpect(jsonPath("$.status").value(404));
    }

    private PosConnection activeConnection(Store store, long companyId) {
        PosConnection connection = new PosConnection();
        connection.setStore(store);
        connection.setCompanyId(companyId);
        connection.setCompanyName("UvoPOS");
        connection.setApiKey("uvp_" + companyId + "_clavedeprueba");
        connection.setWebhookSecret(SECRET);
        connection.setApiUrl("https://pos.invalid");
        connection.setActive(true);
        return posConnectionRepository.save(connection);
    }

    private static String sign(String data) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
    }

    private void expectGone(MockHttpServletRequestBuilder request) throws Exception {
        mockMvc.perform(request)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.error").value("Not Found"))
                .andExpect(jsonPath("$.message").value("El recurso solicitado no existe."))
                .andExpect(jsonPath("$.timestamp").exists());
    }
}
