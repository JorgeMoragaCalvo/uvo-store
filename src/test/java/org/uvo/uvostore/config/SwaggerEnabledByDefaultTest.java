package org.uvo.uvostore.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.uvo.uvostore.support.IntegrationTestSupport;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * PROD-17c. El control de {@link SwaggerDisabledTest}: con la configuración por defecto —la de desarrollo—
 * la documentación SÍ se publica, sin autenticación.
 *
 * <p>Existe para que el test del caso apagado no pueda pasar por el motivo equivocado. Si las rutas que
 * ese test afirma que dan 404 estuvieran mal escritas, darían 404 también aquí y apagado-o-no sería
 * indistinguible. Y fija el problema que el interruptor viene a resolver: lo que se publica es el mapa
 * completo de la API, incluida la de plataforma, a cualquiera que llegue.
 */
class SwaggerEnabledByDefaultTest extends IntegrationTestSupport {

    @Test
    @DisplayName("Por defecto la especificación es pública y publica hasta la API de plataforma")
    void theSpecIsPublicByDefault() throws Exception {
        mockMvc.perform(get("/v3/api-docs/platform"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/platform/stores']").exists());
    }

    @Test
    @DisplayName("Por defecto la interfaz de Swagger responde sin autenticación")
    void theUiIsReachableByDefault() throws Exception {
        // springdoc redirige /swagger-ui.html a /swagger-ui/index.html con su configuración.
        mockMvc.perform(get("/swagger-ui.html"))
                .andExpect(status().is3xxRedirection());
    }
}
