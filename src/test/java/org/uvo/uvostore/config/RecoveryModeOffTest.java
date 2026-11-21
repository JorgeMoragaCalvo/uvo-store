package org.uvo.uvostore.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationContext;
import org.uvo.uvostore.support.IntegrationTestSupport;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * IMP-09. Sin el modo recuperación (lo normal), todo sigue como antes: el planificador está encendido y la API de
 * recuperación responde 409, porque con la conciliación y el outbox corriendo su informe describiría algo que ya
 * cambió.
 */
class RecoveryModeOffTest extends IntegrationTestSupport {

    @Value("${app.platform-api-key}")
    private String platformKey;
    @Autowired
    private ApplicationContext context;

    @Test
    @DisplayName("El planificador está encendido")
    void schedulingIsOn() {
        assertThat(context.getBeanNamesForType(org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor.class).length > 0)
                .isTrue();
    }

    @Test
    @DisplayName("El informe y la retención responden 409 fuera del modo recuperación")
    void recoveryApiIsUnavailable() throws Exception {
        mockMvc.perform(get("/api/platform/recovery/report").param("from", Instant.now().toString())
                        .header("X-Platform-Key", platformKey))
                .andExpect(status().isConflict());
        mockMvc.perform(post("/api/platform/recovery/outbox/hold").header("X-Platform-Key", platformKey)
                        .contentType("application/json").content("{\"restoredAt\":\"" + Instant.now() + "\"}"))
                .andExpect(status().isConflict());
    }
}
