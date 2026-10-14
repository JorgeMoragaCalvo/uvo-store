package org.uvo.uvostore.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import org.uvo.uvostore.support.IntegrationTestSupport;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * PROD-17c. Con {@code SWAGGER_ENABLED=false} no queda nada de la documentación de la API.
 *
 * <p>Antes no había forma de apagarla: {@code /swagger-ui.html} y {@code /v3/api-docs/**} estaban en
 * {@code SecurityConfig.PUBLIC_PATHS} y publicaban el mapa completo de la API —incluida
 * {@code /api/platform/**}— a cualquiera. El control del caso encendido es
 * {@link SwaggerEnabledByDefaultTest}: afirma que estas mismas rutas SÍ responden por defecto.
 *
 * <p>Se afirma 404 y no 401/403 a propósito: con springdoc apagado los endpoints no existen, y que
 * "no hay nada aquí" sea indistinguible de cualquier ruta inexistente es justo lo que se quiere. Por eso
 * las rutas siguen en {@code PUBLIC_PATHS} (ver el comentario allí).
 */
@TestPropertySource(properties = "app.swagger.enabled=false")
class SwaggerDisabledTest extends IntegrationTestSupport {

    @Test
    @DisplayName("La especificación no se publica: ni el agregado, ni un grupo, ni su configuración")
    void noSpecIsPublished() throws Exception {
        // Son las rutas que cubre PUBLIC_PATHS (`/v3/api-docs/**`). `/v3/api-docs.yaml` no está entre
        // ellas —el patrón no la alcanza—, así que un anónimo recibe 401 con la documentación encendida o
        // apagada y no es algo que este interruptor cierre ni abra.
        mockMvc.perform(get("/v3/api-docs")).andExpect(status().isNotFound());
        mockMvc.perform(get("/v3/api-docs/platform")).andExpect(status().isNotFound());
        mockMvc.perform(get("/v3/api-docs/swagger-config")).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("La interfaz de Swagger no se publica")
    void noUiIsPublished() throws Exception {
        mockMvc.perform(get("/swagger-ui.html")).andExpect(status().isNotFound());
        mockMvc.perform(get("/swagger-ui/index.html")).andExpect(status().isNotFound());
    }
}
