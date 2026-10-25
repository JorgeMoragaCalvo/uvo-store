package org.uvo.uvostore.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.uvo.uvostore.support.IntegrationTestSupport;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PROD-15b. Cada pasada de un trabajo programado lleva su propio id en el MDC, y no deja nada en el hilo al terminar.
 */
class SchedulerRequestIdTest extends IntegrationTestSupport {

    @Autowired
    private ThreadPoolTaskScheduler taskScheduler;

    @AfterEach
    void cleanMdc() {
        MDC.clear();
    }

    @Test
    @DisplayName("Una tarea del planificador de la aplicación ve un id job-…, distinto en cada pasada")
    void theApplicationSchedulerGivesEachRunAnId() throws Exception {
        String first = idSeenByAScheduledRun();
        String second = idSeenByAScheduledRun();

        assertThat(first).matches("job-[0-9a-f]{12}");
        assertThat(second).matches("job-[0-9a-f]{12}").isNotEqualTo(first);
        // Cumple el formato que acepta RequestIdFilter, así que el POS y la auditoría lo reciben igual.
        assertThat(RequestIdFilter.acceptedOrNew(first)).isEqualTo(first);
    }

    @Test
    @DisplayName("Al terminar, el hilo vuelve a tener exactamente el MDC de antes: ni el id ni la tienda de la pasada")
    void theThreadIsLeftAsItWas() {
        MDC.put("otraCosa", "se-queda");
        AtomicReference<String> inside = new AtomicReference<>();

        SchedulingConfig.withJobId(() -> {
            inside.set(MDC.get(LogContext.REQUEST_ID));
            // Lo que un trabajo deje olvidado (aquí, la tienda) no puede pasar a la pasada siguiente del mismo hilo.
            LogContext.putStore(99L);
        }).run();

        assertThat(inside.get()).startsWith("job-");
        assertThat(MDC.getCopyOfContextMap()).isEqualTo(Map.of("otraCosa", "se-queda"));
    }

    @Test
    @DisplayName("Desde un hilo con el MDC vacío, también queda vacío")
    void anEmptyThreadStaysEmpty() {
        SchedulingConfig.withJobId(() -> LogContext.putStore(1L)).run();

        assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
    }

    private String idSeenByAScheduledRun() throws Exception {
        AtomicReference<String> seen = new AtomicReference<>();
        taskScheduler.schedule(() -> seen.set(MDC.get(LogContext.REQUEST_ID)), Instant.now())
                .get(5, TimeUnit.SECONDS);
        return seen.get();
    }
}
