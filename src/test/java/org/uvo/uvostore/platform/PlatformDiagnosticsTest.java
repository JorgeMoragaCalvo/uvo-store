package org.uvo.uvostore.platform;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.uvo.uvostore.service.platform.SentryDiagnostics;
import org.uvo.uvostore.support.IntegrationTestSupport;

import java.util.Optional;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** IMP-03. El evento de prueba de Sentry: solo para el operador, y dice si Sentry está configurado. */
class PlatformDiagnosticsTest extends IntegrationTestSupport {

    private static final String PATH = "/api/platform/diagnostics/sentry-test";

    @Value("${app.platform-api-key}")
    private String platformKey;

    @MockitoBean
    private SentryDiagnostics sentryDiagnostics;

    @Test
    @DisplayName("Sin la clave de plataforma no se envía nada")
    void needsThePlatformKey() throws Exception {
        mockMvc.perform(post(PATH)).andExpect(status().isUnauthorized());
        mockMvc.perform(post(PATH).header("X-Platform-Key", "otra")).andExpect(status().isUnauthorized());

        verify(sentryDiagnostics, never()).sendTestEvent();
    }

    @Test
    @DisplayName("Con Sentry configurado responde el id del evento")
    void returnsTheEventId() throws Exception {
        when(sentryDiagnostics.sendTestEvent()).thenReturn(Optional.of("0f1e2d3c4b5a69788796a5b4c3d2e1f0"));

        mockMvc.perform(post(PATH).header("X-Platform-Key", platformKey))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.eventId").value("0f1e2d3c4b5a69788796a5b4c3d2e1f0"));
    }

    @Test
    @DisplayName("Sin SENTRY_DSN responde 409 y lo dice")
    void saysWhenSentryIsNotConfigured() throws Exception {
        when(sentryDiagnostics.sendTestEvent()).thenReturn(Optional.empty());

        mockMvc.perform(post(PATH).header("X-Platform-Key", platformKey))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("SENTRY_DSN")));
    }
}
