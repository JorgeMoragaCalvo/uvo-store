package org.uvo.uvostore.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;
import org.uvo.uvostore.security.MetricsAuthFilter;
import org.uvo.uvostore.support.IntegrationTestSupport;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * PROD-15b. Sin {@code METRICS_TOKEN} (el default), {@code /actuator/prometheus} no existe, aunque el exportador esté
 * encendido ({@link AutoConfigureMetrics}: sin él, el 404 sería el de un endpoint ausente y no probaría nada).
 */
@AutoConfigureMetrics
class MetricsWithoutTokenTest extends IntegrationTestSupport {

    @Test
    @DisplayName("Sin token configurado es 404, con o sin cabecera: abrirlo es una decisión del despliegue")
    void withoutAConfiguredTokenTheRouteDoesNotExist() throws Exception {
        mockMvc.perform(get(MetricsAuthFilter.PATH)).andExpect(status().isNotFound());
        // Un "Bearer " vacío no puede coincidir con un token vacío.
        mockMvc.perform(get(MetricsAuthFilter.PATH).header("Authorization", "Bearer ")).andExpect(status().isNotFound());
        mockMvc.perform(get(MetricsAuthFilter.PATH).header("Authorization", "Bearer cualquiera"))
                .andExpect(status().isNotFound());
    }
}
