package org.uvo.uvostore.config;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.PatternLayout;
import ch.qos.logback.classic.spi.LoggingEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.web.servlet.MvcResult;
import org.uvo.uvostore.support.IntegrationTestSupport;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * PROD-18. El id de la petición con la aplicación entera: llega también a las respuestas que escribe Spring Security
 * (el filtro va delante de su cadena) y aparece en el log.
 */
@ExtendWith(OutputCaptureExtension.class)
class RequestIdIntegrationTest extends IntegrationTestSupport {

    @Value("${logging.pattern.correlation}")
    private String correlationPattern;

    @Test
    @DisplayName("Un 401 de Spring Security también lleva el id: el filtro va delante de toda la cadena")
    void aSecurityRejectionCarriesTheId() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/admin/products").header("Host", "demo.localhost"))
                .andExpect(status().isUnauthorized())
                .andReturn();

        assertThat(UUID.fromString(result.getResponse().getHeader(RequestIdFilter.HEADER))).isNotNull();
    }

    @Test
    @DisplayName("El id entrante vuelve en la respuesta y aparece entre corchetes en el log de esa petición")
    void theIdReachesTheLog(CapturedOutput output) throws Exception {
        // El 404 de red de la API de plataforma escribe un WARN: es una línea de log de esta petición.
        MvcResult result = mockMvc.perform(post("/api/platform/stores")
                        .with(request -> {
                            request.setRemoteAddr("203.0.113.7");
                            return request;
                        })
                        .header(RequestIdFilter.HEADER, "it-req-0001-abcd")
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isNotFound())
                .andReturn();

        assertThat(result.getResponse().getHeader(RequestIdFilter.HEADER)).isEqualTo("it-req-0001-abcd");
        assertThat(output.getOut()).contains("[it-req-0001-abcd] ");
    }

    @Test
    @DisplayName("El espacio final va dentro de un grupo: suelto al final del valor, la carga de propiedades lo recorta")
    void theTrailingSpaceSurvivesInsideAGroup() {
        // Pasó de verdad: con "[%X{requestId:-}] " el log salía "[id]o.u.u.security..." (theIdReachesTheLog).
        assertThat(correlationPattern).isEqualTo("%([%X{requestId:-}] ){}");
    }

    @Test
    @DisplayName("El patrón de producción (logback-spring.xml, perfil !default) muestra el id y el nombre del logger")
    void theProductionPatternRendersTheIdAndTheLogger() throws Exception {
        // Este test corre con el perfil default, así que el patrón propio no se ve en theIdReachesTheLog. Pasó de
        // verdad, en la prueba manual con el jar: logback toma como literal lo que sigue a un ")", y sin el {} final
        // de la propiedad cada línea decía "[] %logger{40} - ..." en vez del logger.
        String xml = new String(new ClassPathResource("logback-spring.xml").getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);
        Matcher matcher = Pattern.compile("name=\"!default\">.*?<pattern>(.*?)</pattern>", Pattern.DOTALL).matcher(xml);
        assertThat(matcher.find()).isTrue();
        String pattern = matcher.group(1).replace("${LOG_CORRELATION_PATTERN:-}", correlationPattern);

        LoggerContext context = new LoggerContext();
        PatternLayout layout = new PatternLayout();
        layout.setContext(context);
        layout.setPattern(pattern);
        layout.start();
        LoggingEvent event = new LoggingEvent(getClass().getName(),
                context.getLogger("org.uvo.uvostore.security.PlatformApiKeyAuthFilter"), Level.WARN, "mensaje", null, null);
        event.setMDCPropertyMap(Map.of(RequestIdFilter.MDC_KEY, "it-req-0002-abcd"));

        assertThat(layout.doLayout(event))
                .contains("[it-req-0002-abcd] o.u.u.security.PlatformApiKeyAuthFilter - mensaje")
                .doesNotContain("%logger");
    }
}
