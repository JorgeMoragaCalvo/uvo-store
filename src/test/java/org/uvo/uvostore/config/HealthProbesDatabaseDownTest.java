package org.uvo.uvostore.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.uvo.uvostore.support.IntegrationTestSupport;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * PROD-15a. La razón de que existan dos sondas: con la base caída, readiness dice 503 (sácame del
 * balanceador) y liveness sigue diciendo 200 (no me mates).
 *
 * <p>Si la base formara parte de liveness, cada caída de la base provocaría reinicios en cadena de todas
 * las instancias, justo cuando más estorba: cada una arranca, no encuentra la base, falla la sonda y vuelve
 * a ser reiniciada, mientras en la memoria se pierden la caché de versiones de token y los contadores del
 * limitador.
 *
 * <p>La base "caída" se simula con un bean llamado {@code dbHealthIndicator}: es el nombre ante el que la
 * autoconfiguración de Boot se aparta ({@code @ConditionalOnMissingBean(name = "dbHealthIndicator")}), de
 * modo que el contribuyente {@code db} real queda reemplazado por este y las sondas lo recogen igual que
 * recogerían el verdadero.
 */
@Import(HealthProbesDatabaseDownTest.DatabaseDown.class)
class HealthProbesDatabaseDownTest extends IntegrationTestSupport {

    @TestConfiguration
    static class DatabaseDown {

        @Bean
        HealthIndicator dbHealthIndicator() {
            return () -> Health.down().build();
        }
    }

    @Test
    @DisplayName("Con la base caída, readiness responde 503")
    void readinessGoesDownWhenTheDatabaseDoes() throws Exception {
        mockMvc.perform(get("/actuator/health/readiness"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value("DOWN"));
    }

    @Test
    @DisplayName("Con la base caída, liveness sigue respondiendo 200: reiniciar la aplicación no arregla la base")
    void livenessStaysUpWhenTheDatabaseGoesDown() throws Exception {
        mockMvc.perform(get("/actuator/health/liveness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }
}
