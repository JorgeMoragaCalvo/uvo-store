package org.uvo.uvostore.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.health.actuate.endpoint.HealthEndpointGroup;
import org.springframework.boot.health.actuate.endpoint.HealthEndpointGroups;
import org.uvo.uvostore.support.IntegrationTestSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * PROD-15a. Las dos sondas que un orquestador necesita, por separado y sin autenticar.
 *
 * <p>Antes solo existía {@code /actuator/health}, un único veredicto que mezcla "¿hay que reiniciar el
 * proceso?" con "¿le mando tráfico?". El caso que importa —la base cae y el orquestador reinicia la
 * aplicación una y otra vez— está en {@link HealthProbesDatabaseDownTest}; aquí se fija la forma de la
 * configuración: quién es miembro de qué grupo, qué rutas son públicas y qué no se filtra.
 */
class HealthProbesTest extends IntegrationTestSupport {

    @Autowired
    private HealthEndpointGroups groups;

    @Test
    @DisplayName("La sonda de liveness responde sin autenticar, y sin dar detalles")
    void livenessIsPublicAndShowsNoDetails() throws Exception {
        mockMvc.perform(get("/actuator/health/liveness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                // show-details=never: ni los componentes ni lo que haya detrás de la base viajan a un anónimo.
                .andExpect(jsonPath("$.components").doesNotExist())
                .andExpect(jsonPath("$.details").doesNotExist());
    }

    @Test
    @DisplayName("La sonda de readiness responde sin autenticar, y sin dar detalles")
    void readinessIsPublicAndShowsNoDetails() throws Exception {
        mockMvc.perform(get("/actuator/health/readiness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components").doesNotExist())
                .andExpect(jsonPath("$.details").doesNotExist());
    }

    @Test
    @DisplayName("Abrir las dos sondas no abre los componentes de salud ni el resto de actuator")
    void openingTheProbesDoesNotOpenAnythingElse() throws Exception {
        // PUBLIC_PATHS nombra las sondas una a una: `/actuator/health/**` abriría también esto.
        mockMvc.perform(get("/actuator/health/db")).andExpect(status().is4xxClientError());
        mockMvc.perform(get("/actuator/info")).andExpect(status().is4xxClientError());
    }

    @Test
    @DisplayName("La base es miembro de readiness y no de liveness")
    void theDatabaseGatesReadinessButNotLiveness() {
        HealthEndpointGroup liveness = groups.get("liveness");
        HealthEndpointGroup readiness = groups.get("readiness");

        assertThat(liveness).as("grupo liveness").isNotNull();
        assertThat(readiness).as("grupo readiness").isNotNull();

        // Sacar la app del balanceador cuando no hay base es lo correcto; reiniciarla no lo es: vuelve a
        // arrancar contra la misma base caída y, además, pierde las cachés y los contadores en memoria.
        assertThat(readiness.isMember("db")).as("db en readiness").isTrue();
        assertThat(liveness.isMember("db")).as("db en liveness").isFalse();
        assertThat(liveness.isMember("livenessState")).isTrue();
        assertThat(readiness.isMember("readinessState")).isTrue();
    }
}
