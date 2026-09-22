package org.uvo.uvostore.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * R1. Hasta aquí el proyecto no tenía un solo {@code @EnableAsync}, así que los tres listeners
 * {@code AFTER_COMMIT} del checkout corrían en el hilo de la petición. Eso metía dentro de la
 * respuesta al comprador dos llamadas de red ajenas: la notificación al POS y el correo de
 * confirmación. Con el SMTP sin timeout —el default de JavaMail es esperar para siempre— bastaba un
 * relay que aceptara la conexión y no contestara para ir dejando hilos de Tomcat colgados hasta
 * agotar el pool, y entonces lo que dejaba de responder era la tienda entera, no el correo.
 *
 * <p><b>Dos executors y no uno, a propósito.</b> Es un mamparo: si el POS se cae y llena su cola, el
 * correo sigue teniendo hilos propios para salir, y al revés. Compartir un pool convierte el fallo de
 * cualquiera de los dos en el fallo de ambos.
 *
 * <p><b>Y la cola es acotada, con {@code CallerRunsPolicy}.</b> Cuando se llena, el trabajo lo ejecuta
 * el hilo que lo publicó en vez de descartarse. Descartar sería peor de lo que parece: la selección de
 * {@code PosNotificationRetryJob} exige {@code syncAttempts >= 1}, así que una notificación que nunca
 * llegó a intentarse quedaría invisible para el reintento — que además viene desactivado por defecto
 * hasta que se confirme el contrato SII. El peor caso pasa a ser "lento", y acotado por los timeouts
 * de {@code PosClient} y de {@code spring.mail.*}; nunca "perdido".
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    public static final String POS_EXECUTOR = "posExecutor";
    public static final String MAIL_EXECUTOR = "mailExecutor";

    @Bean(POS_EXECUTOR)
    public ThreadPoolTaskExecutor posExecutor(
            @Value("${app.async.pos.core-size:2}") int coreSize,
            @Value("${app.async.pos.max-size:4}") int maxSize,
            @Value("${app.async.pos.queue-capacity:500}") int queueCapacity) {
        return executor("pos-", coreSize, maxSize, queueCapacity);
    }

    @Bean(MAIL_EXECUTOR)
    public ThreadPoolTaskExecutor mailExecutor(
            @Value("${app.async.mail.core-size:2}") int coreSize,
            @Value("${app.async.mail.max-size:4}") int maxSize,
            @Value("${app.async.mail.queue-capacity:500}") int queueCapacity) {
        return executor("mail-", coreSize, maxSize, queueCapacity);
    }

    private ThreadPoolTaskExecutor executor(String namePrefix, int coreSize, int maxSize, int queueCapacity) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(coreSize);
        executor.setMaxPoolSize(maxSize);
        executor.setQueueCapacity(queueCapacity);
        // El prefijo no es cosmético: es lo que permite ver en un hilo colgado de qué integración es,
        // y lo que usan los tests para comprobar que esto de verdad cambió de hilo.
        executor.setThreadNamePrefix(namePrefix);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        // Un redespliegue no debe tirar a la basura una notificación ya aceptada. El margen va por
        // debajo de spring.lifecycle.timeout-per-shutdown-phase (20s) para que el apagado ordenado
        // siga siendo ordenado y no lo corte la plataforma por tardón.
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(15);
        executor.initialize();
        return executor;
    }
}
