package org.uvo.uvostore.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.uvo.uvostore.security.MetricsAuthFilter;
import org.uvo.uvostore.support.IntegrationTestSupport;

import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * PROD-15b. {@code /actuator/prometheus} con un token configurado: las tres puertas de {@link MetricsAuthFilter} y
 * lo que se publica.
 *
 * <p>{@link AutoConfigureMetrics} porque los tests de Boot usan por defecto un registro en memoria y apagan los
 * exportadores: sin él la ruta no existiría y cualquier 404 pasaría por bueno. El caso sin token es
 * {@link MetricsWithoutTokenTest}, con el registro encendido por la misma razón.
 */
@AutoConfigureMetrics
@TestPropertySource(properties = "app.metrics.token=" + MetricsEndpointTest.TOKEN)
class MetricsEndpointTest extends IntegrationTestSupport {

    static final String TOKEN = "token-de-metricas-solo-para-tests-0123456789abcdef";

    @Test
    @DisplayName("Con el token correcto, desde la red privada: las métricas en formato Prometheus")
    void theRightTokenGetsTheMetrics() throws Exception {
        String body = mockMvc.perform(scrape().header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body)
                .contains("uvostore_outbox_pending{application=\"uvo-store\",channel=\"pos\"}")
                .contains("uvostore_outbox_failed{application=\"uvo-store\",channel=\"mail\"}")
                .contains("uvostore_outbox_oldest_pending_seconds{application=\"uvo-store\",channel=\"pos\"}")
                .contains("uvostore_orders_paid_without_stock{")
                .contains("uvostore_orders_pending_stale{")
                .contains("uvostore_refunds_pending{")
                .contains("uvostore_pos_stock_divergences_total{application=\"uvo-store\",source=\"webhook\"}")
                .contains("uvostore_outbox_deliveries_total{application=\"uvo-store\",channel=\"pos\",outcome=\"uncertain\"}")
                // Lo que Boot da sin código: la cola del planificador (el "tamaño de las colas" de la tarea), la JVM
                // y el pool de la base.
                .contains("executor_queued_tasks{")
                .contains("jvm_memory_used_bytes{")
                .contains("hikaricp_connections_active{");
    }

    @Test
    @DisplayName("Ninguna serie propia lleva la tienda como etiqueta")
    void noSeriesIsLabelledByStore() throws Exception {
        String body = mockMvc.perform(scrape().header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        List<String> own = Arrays.stream(body.split("\n")).filter(line -> line.startsWith("uvostore_")).toList();
        // Las claves de las etiquetas, no la línea: el nombre ("uvostore_") y el valor de application ("uvo-store")
        // ya dicen "store".
        Pattern storeLabel = Pattern.compile("[{,][a-z_]*store[a-z_]*=");
        assertThat(own).isNotEmpty().noneMatch(line -> storeLabel.matcher(line).find());
    }

    @Test
    @DisplayName("Sin token, con otro esquema o con uno equivocado: 401")
    void aMissingOrWrongTokenIs401() throws Exception {
        mockMvc.perform(scrape()).andExpect(status().isUnauthorized());
        mockMvc.perform(scrape().header("Authorization", "Bearer " + TOKEN + "x")).andExpect(status().isUnauthorized());
        mockMvc.perform(scrape().header("Authorization", "Bearer token-equivocado")).andExpect(status().isUnauthorized());
        mockMvc.perform(scrape().header("Authorization", "Basic " + TOKEN)).andExpect(status().isUnauthorized());
        mockMvc.perform(scrape().header("Authorization", TOKEN)).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Desde fuera de la red privada es una ruta que no existe, aunque el token sea el bueno")
    void fromOutsideTheNetworkItDoesNotExist() throws Exception {
        mockMvc.perform(scrape()
                        .with(request -> {
                            request.setRemoteAddr("203.0.113.7");
                            return request;
                        })
                        .header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("Abrir prometheus no abre /actuator/metrics, que desglosa cada métrica")
    void theMetricsEndpointStaysClosed() throws Exception {
        mockMvc.perform(get("/actuator/metrics").header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().is4xxClientError());
        mockMvc.perform(get("/actuator/metrics/jvm.memory.used").header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().is4xxClientError());
    }

    private static MockHttpServletRequestBuilder scrape() {
        return get(MetricsAuthFilter.PATH);
    }
}
