package org.uvo.uvostore.pos;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.uvo.uvostore.entity.pos.PosConnection;
import org.uvo.uvostore.entity.tenant.Store;
import org.uvo.uvostore.repository.PosConnectionRepository;
import org.uvo.uvostore.support.IntegrationTestSupport;


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
    @Autowired
    private org.uvo.uvostore.repository.PosConnectionKeyRepository keyRepository;

    @Test
    @DisplayName("Un webhook correctamente firmado por una conexión activa responde 404")
    void aValidWebhookIsGone() throws Exception {
        Store store = createStore("pos-off-webhook");
        PosConnection connection = activeConnection(store, 4242L);
        String body = "{\"companyId\":" + connection.getCompanyId() + ",\"productId\":1,\"version\":2,"
                + "\"stockWeb\":4}";

        expectGone(org.uvo.uvostore.support.PosSigning.signedPost("/api/webhooks/pos/stock-updated", body, connection)
                .header("Host", hostHeader(store)));
    }

    @Test
    @DisplayName("La carga de productos con la firma correcta responde 404")
    void theSyncSurfaceIsGone() throws Exception {
        Store store = createStore("pos-off-sync");
        PosConnection connection = activeConnection(store, 4343L);

        expectGone(org.uvo.uvostore.support.PosSigning.signedPost("/api/sync/product",
                        "{\"companyId\":" + connection.getCompanyId() + ",\"name\":\"Algo\",\"price\":1000}", connection)
                .header("Host", hostHeader(store)));
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
                // 401 de el filtro de firma del POS. El contraste con PosWebhookAuthTest —que espera
                // exactamente ese 401 con la integración encendida— es lo que prueba el orden.
                .andExpect(header().doesNotExist("WWW-Authenticate"))
                .andExpect(jsonPath("$.status").value(404));
    }

    private PosConnection activeConnection(Store store, long companyId) {
        PosConnection connection = new PosConnection();
        connection.setStore(store);
        connection.setCompanyId(companyId);
        connection.setCompanyName("UvoPOS");
        connection.setApiUrl("https://pos.invalid");
        connection.setActive(true);
        return org.uvo.uvostore.support.PosSigning.installKeys(keyRepository, posConnectionRepository.save(connection));
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
