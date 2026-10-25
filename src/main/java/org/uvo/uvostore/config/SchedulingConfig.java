package org.uvo.uvostore.config;

import java.util.Map;
import org.slf4j.MDC;
import org.springframework.boot.task.ThreadPoolTaskSchedulerCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskDecorator;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * G1/G2. El proyecto no tenía un solo {@code @Scheduled}, {@code @Retryable} ni cola: todo lo que
 * fallaba después de confirmar la transacción —una notificación al POS, un webhook de pago que no
 * llegó— se perdía sin que nada volviera a mirarlo. Los dos trabajos que arreglan eso necesitan que
 * el planificador esté encendido, y esto es lo que lo enciende.
 *
 * <p>Va en su propia clase, y no como anotación suelta en {@code UvoStoreApplication}, para que
 * quede un sitio donde explicar por qué existe.
 *
 * <p>PROD-15b: cada pasada de un trabajo programado lleva su propio id en el MDC ({@code job-…}, ver
 * {@link LogContext#newJobId()}), así sus líneas de log se agrupan como las de una petición, y lo que
 * escriba (una fila de auditoría, una fila del outbox que acabe en el POS) queda con ese id. Va como
 * decorador del planificador y no en cada {@code @Scheduled}: así cubre los siete sin tocarlos, y
 * también al que se añada mañana.
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {

    /**
     * Un customizer y no un bean {@link TaskDecorator}: Boot aplicaría ese bean también al ejecutor de
     * tareas de la aplicación, y lo que corre ahí no es una pasada de job.
     */
    @Bean
    public ThreadPoolTaskSchedulerCustomizer jobRequestIdCustomizer() {
        return scheduler -> scheduler.setTaskDecorator(SchedulingConfig::withJobId);
    }

    /**
     * El id se genera dentro de {@code run()}, no al decorar: un {@code fixedDelay} se decora una sola vez y
     * corre muchas, y cada pasada tiene que tener el suyo. Al salir se restaura el MDC entero: un hilo del
     * pool no puede quedarse con el id ni con la tienda de la pasada anterior.
     */
    static Runnable withJobId(Runnable task) {
        return () -> {
            Map<String, String> previous = MDC.getCopyOfContextMap();
            LogContext.putRequestId(LogContext.newJobId());
            try {
                task.run();
            } finally {
                if (previous == null) {
                    MDC.clear();
                } else {
                    MDC.setContextMap(previous);
                }
            }
        };
    }
}
