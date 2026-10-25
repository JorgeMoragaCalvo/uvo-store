package org.uvo.uvostore.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.uvo.uvostore.support.IntegrationTestSupport;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * POS-6. {@code /api/platform/**} solo responde desde la red privada ({@code PLATFORM_ALLOWED_NETWORKS}, por defecto
 * loopback y los rangos privados). Desde fuera es un 404, y la clave ni se mira.
 *
 * <p>Para saber si una petición pasó el filtro sin crear nada se manda un cuerpo vacío: pasado el filtro, la
 * validación responde 400, que no se confunde ni con el 404 de la red ni con el 401 de la clave.
 *
 * <p>Las direcciones públicas son de documentación (RFC 5737). El caso con un proxy confiable delante está en
 * {@link PlatformNetworkBehindProxyTest}, con su propio contexto.
 */
class PlatformNetworkTest extends IntegrationTestSupport {

    @Value("${app.platform-api-key}")
    private String platformKey;

    @Test
    @DisplayName("Desde una IP pública es un 404, aunque la clave sea la correcta")
    void aPublicAddressGetsNotFoundEvenWithTheRightKey() throws Exception {
        mockMvc.perform(platformCall("203.0.113.9").header("X-Platform-Key", platformKey))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("El recurso solicitado no existe."));
    }

    @Test
    @DisplayName("Desde una IP pública la clave ni se mira: una mala también es 404, no 401")
    void fromOutsideTheKeyIsNotEvenChecked() throws Exception {
        // Un 401 le diría a quien prueba claves desde Internet que llegó a la puerta.
        mockMvc.perform(platformCall("203.0.113.9").header("X-Platform-Key", "clave-equivocada"))
                .andExpect(status().isNotFound());
        mockMvc.perform(platformCall("203.0.113.9"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("Desde la red privada se llega a la clave: sin ella es 401, con ella pasa")
    void fromThePrivateNetworkTheKeyDecides() throws Exception {
        mockMvc.perform(platformCall("10.0.0.5"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(platformCall("10.0.0.5").header("X-Platform-Key", platformKey))
                .andExpect(status().isBadRequest());
        mockMvc.perform(platformCall("192.168.1.20").header("X-Platform-Key", platformKey))
                .andExpect(status().isBadRequest());
        // Las dos formas en que puede llegar el loopback IPv6.
        mockMvc.perform(platformCall("0:0:0:0:0:0:0:1").header("X-Platform-Key", platformKey))
                .andExpect(status().isBadRequest());
        mockMvc.perform(platformCall("::1").header("X-Platform-Key", platformKey))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Una cabecera de reenvío sin proxy confiable declarado es un 404, aunque todo parezca privado")
    void aForwardedRequestWithoutATrustedProxyIsRejected() throws Exception {
        // Detrás de un proxy no declarado, el socket es la dirección privada del proxy y la cabecera puede decir
        // cualquier cosa: no se puede saber de dónde viene, así que no pasa.
        mockMvc.perform(platformCall("127.0.0.1")
                        .header("X-Platform-Key", platformKey)
                        .header("X-Forwarded-For", "10.0.0.5"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("PROD-18. El operador se valida después de la red y de la clave: sin pasarlas no se puede sondear")
    void theOperatorIsCheckedAfterTheNetworkAndTheKey() throws Exception {
        mockMvc.perform(platformCall("203.0.113.9")
                        .header("X-Platform-Key", platformKey)
                        .header("X-Platform-Operator", "mal formado"))
                .andExpect(status().isNotFound());
        mockMvc.perform(platformCall("10.0.0.5")
                        .header("X-Platform-Key", "clave-equivocada")
                        .header("X-Platform-Operator", "mal formado"))
                .andExpect(status().isUnauthorized());
        // El cuerpo vacío también daría 400 (validación): lo que dice que fue el operador es el mensaje.
        mockMvc.perform(platformCall("10.0.0.5")
                        .header("X-Platform-Key", platformKey)
                        .header("X-Platform-Operator", "mal formado"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("X-Platform-Operator")));
    }

    @Test
    @DisplayName("PROD-23. La operación de tiendas está detrás de la misma puerta: 404 desde fuera, 401 con una clave mala")
    void theStoreOperationsAreBehindTheSameGate() throws Exception {
        for (MockHttpServletRequestBuilder call : List.of(
                get("/api/platform/stores"),
                get("/api/platform/stores/1/audit-events"),
                post("/api/platform/stores/1/suspend").contentType("application/json").content("{\"reason\":\"OTHER\"}"),
                post("/api/platform/stores/1/close").contentType("application/json").content("{}"))) {
            mockMvc.perform(from(call, "203.0.113.9").header("X-Platform-Key", platformKey))
                    .andExpect(status().isNotFound());
        }
        mockMvc.perform(from(get("/api/platform/stores"), "10.0.0.5").header("X-Platform-Key", "clave-equivocada"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(from(get("/api/platform/stores"), "10.0.0.5").header("X-Platform-Key", platformKey))
                .andExpect(status().isOk());
    }

    private static MockHttpServletRequestBuilder from(MockHttpServletRequestBuilder call, String remoteAddress) {
        return call.with(request -> {
            request.setRemoteAddr(remoteAddress);
            return request;
        });
    }

    @Test
    @DisplayName("El resto de la API no se ve afectado por la lista de redes")
    void otherRoutesAreUntouched() throws Exception {
        mockMvc.perform(post("/api/admin/auth/login")
                        .with(request -> {
                            request.setRemoteAddr("203.0.113.9");
                            return request;
                        })
                        .header("Host", "demo.localhost")
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Una entrada mal escrita en la lista impide el arranque, y una lista vacía no deja pasar a nadie")
    void aMistypedListFailsTheStartupAndAnEmptyOneLetsNobodyIn() {
        ClientIpResolver resolver = new ClientIpResolver("");

        assertThatThrownBy(() -> new PlatformNetworkPolicy("10.0.0.0/8, 10.0.0", resolver))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PLATFORM_ALLOWED_NETWORKS")
                .hasMessageContaining("10.0.0");

        MockHttpServletRequest loopback = new MockHttpServletRequest();
        loopback.setRemoteAddr("127.0.0.1");
        assertThat(new PlatformNetworkPolicy("", resolver).allows(loopback)).isFalse();
        assertThat(new PlatformNetworkPolicy("127.0.0.0/8", resolver).allows(loopback)).isTrue();
    }

    private MockHttpServletRequestBuilder platformCall(String remoteAddress) {
        return post("/api/platform/stores")
                .with(request -> {
                    request.setRemoteAddr(remoteAddress);
                    return request;
                })
                .contentType("application/json")
                .content("{}");
    }
}
