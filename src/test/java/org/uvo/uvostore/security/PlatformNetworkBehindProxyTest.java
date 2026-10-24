package org.uvo.uvostore.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.uvo.uvostore.support.IntegrationTestSupport;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * POS-6. La lista de redes de la API de plataforma con un proxy inverso confiable delante: la dirección que cuenta
 * es la del cliente que dice el proxy, no la del proxy.
 *
 * <p>Las mismas propiedades que {@link TrustedProxyRateLimitTest}, a propósito: el contexto se cachea por ese
 * conjunto, así que los dos comparten uno solo. Lo único que importa aquí es el proxy {@code 203.0.113.7}.
 */
@SpringBootTest(properties = {
        "app.rate-limit.login=3",
        "app.rate-limit.window-seconds=60",
        "app.rate-limit.trusted-proxies=203.0.113.7"
})
class PlatformNetworkBehindProxyTest extends IntegrationTestSupport {

    @Value("${app.platform-api-key}")
    private String platformKey;

    @Test
    @DisplayName("Un cliente de Internet reenviado por el proxy es un 404, aunque el proxy sea privado")
    void aPublicClientBehindTheProxyIsRejected() throws Exception {
        mockMvc.perform(throughTheProxy("198.51.100.9"))
                .andExpect(status().isNotFound());
        // Y el atacante no se libra poniendo una IP privada a la izquierda: el proxy añade la suya a la derecha.
        mockMvc.perform(throughTheProxy("10.0.0.5, 198.51.100.9"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("Un cliente de la red privada reenviado por el proxy pasa")
    void aPrivateClientBehindTheProxyPasses() throws Exception {
        mockMvc.perform(throughTheProxy("10.0.0.5"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Si el proxy no dice de quién viene la petición, no pasa")
    void aProxyThatDoesNotSayWhoIsBehindIsRejected() throws Exception {
        mockMvc.perform(throughTheProxy(null))
                .andExpect(status().isNotFound());
    }

    private MockHttpServletRequestBuilder throughTheProxy(String forwardedFor) {
        MockHttpServletRequestBuilder builder = post("/api/platform/stores")
                .with(request -> {
                    request.setRemoteAddr("203.0.113.7");
                    return request;
                })
                .header("X-Platform-Key", platformKey)
                .contentType("application/json")
                .content("{}");
        return forwardedFor == null ? builder : builder.header("X-Forwarded-For", forwardedFor);
    }
}
