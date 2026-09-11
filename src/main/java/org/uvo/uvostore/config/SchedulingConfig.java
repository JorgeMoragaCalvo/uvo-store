package org.uvo.uvostore.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * G1/G2. El proyecto no tenía un solo {@code @Scheduled}, {@code @Retryable} ni cola: todo lo que
 * fallaba después de confirmar la transacción —una notificación al POS, un webhook de pago que no
 * llegó— se perdía sin que nada volviera a mirarlo. Los dos trabajos que arreglan eso necesitan que
 * el planificador esté encendido, y esto es lo que lo enciende.
 *
 * <p>Va en su propia clase, y no como anotación suelta en {@code UvoStoreApplication}, para que
 * quede un sitio donde explicar por qué existe.
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
