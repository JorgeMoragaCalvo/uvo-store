package org.uvo.uvostore.config;

import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.task.TaskSchedulingProperties;
import org.springframework.boot.web.server.autoconfigure.ServerProperties;
import org.springframework.boot.web.server.Shutdown;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.uvo.uvostore.config.AsyncConfig;
import org.uvo.uvostore.support.IntegrationTestSupport;

import java.util.List;
import java.util.Properties;
import java.util.concurrent.ThreadPoolExecutor;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B7. Todo esto vivía en los defaults del framework y ahora está configurado explícitamente. El test
 * no comprueba que los valores sean buenos —eso es criterio— sino que **se estén aplicando**: una
 * propiedad con el nombre mal escrito en `application.properties` no da error, no aparece en ningún
 * log y simplemente no hace nada. Es el modo de fallo real de este tipo de configuración, y el único
 * que un test puede atrapar.
 */
class ApplicationConfigurationTest extends IntegrationTestSupport {

    @Autowired
    private ServerProperties serverProperties;
    @Autowired
    private EntityManagerFactory entityManagerFactory;
    @Autowired
    private JavaMailSender mailSender;
    @Autowired
    private TaskSchedulingProperties taskSchedulingProperties;
    @Autowired
    @Qualifier(AsyncConfig.POS_EXECUTOR)
    private ThreadPoolTaskExecutor posExecutor;
    @Autowired
    @Qualifier(AsyncConfig.MAIL_EXECUTOR)
    private ThreadPoolTaskExecutor mailExecutor;

    @Test
    @DisplayName("El apagado es ordenado, no un corte en seco")
    void shutdownIsGraceful() {
        // Sin esto, un redespliegue corta la petición en curso: con un checkout a medias eso deja una
        // orden creada cuyo cobro nadie sabe si salió.
        assertThat(serverProperties.getShutdown()).isEqualTo(Shutdown.GRACEFUL);
    }

    @Test
    @DisplayName("Hibernate escribe las fechas en UTC, no en la zona de la máquina")
    void hibernateWritesTimestampsInUtc() {
        // La otra mitad de B5/V17. Sin esta propiedad, la misma fila escrita desde un servidor en
        // America/Santiago y leída desde uno en UTC devuelve otra hora.
        assertThat(entityManagerFactory.getProperties())
                .containsEntry("hibernate.jdbc.time_zone", "UTC");
    }

    @Test
    @DisplayName("El correo tiene timeouts: sin ellos, JavaMail espera para siempre")
    void mailHasTimeouts() {
        // R1, y es el más importante de este archivo. El default de JavaMail es esperar
        // indefinidamente, y una espera infinita no lanza excepción: el try/catch de EmailServiceImpl
        // no protege de nada contra eso. Con el envío en el hilo de la petición (como estaba), un
        // relay que acepta la conexión y no contesta agotaba el pool de Tomcat y tumbaba la tienda.
        Properties properties = ((JavaMailSenderImpl) mailSender).getJavaMailProperties();
        assertThat(properties.getProperty("mail.smtp.connectiontimeout")).isNotBlank();
        assertThat(properties.getProperty("mail.smtp.timeout")).isNotBlank();
        assertThat(properties.getProperty("mail.smtp.writetimeout")).isNotBlank();
    }

    @Test
    @DisplayName("El planificador tiene más de un hilo para sus dos trabajos")
    void theSchedulerIsNotASingleThread() {
        // R1. El default de Spring es 1, y hay dos @Scheduled: PosNotificationRetryJob y
        // PaymentReconciliationService. Con un solo hilo, el que se quedara esperando a una pasarela
        // lenta dejaba al otro sin correr, sin error y sin log.
        assertThat(taskSchedulingProperties.getPool().getSize()).isGreaterThan(1);
    }

    @Test
    @DisplayName("Cada integración tiene su propio pool, y ninguno descarta trabajo")
    void eachIntegrationHasItsOwnBoundedExecutor() {
        // R1. Dos executors y no uno es un mamparo: un POS caído que llene su cola no puede dejar al
        // correo sin hilos. Y CallerRunsPolicy es lo que garantiza que una cola llena degrade a lento
        // en vez de a perdido — descartar una notificación al POS la deja invisible para el reintento,
        // que selecciona por syncAttempts >= 1.
        for (ThreadPoolTaskExecutor executor : List.of(posExecutor, mailExecutor)) {
            assertThat(executor.getThreadPoolExecutor().getRejectedExecutionHandler())
                    .isInstanceOf(ThreadPoolExecutor.CallerRunsPolicy.class);
            assertThat(executor.getThreadPoolExecutor().getQueue().remainingCapacity())
                    .as("la cola tiene que ser acotada, no infinita")
                    .isLessThan(Integer.MAX_VALUE);
        }
        assertThat(posExecutor).isNotSameAs(mailExecutor);
    }

    @Test
    @DisplayName("Los insert van en lote y ordenados por tabla")
    void insertsAreBatched() {
        // order_inserts es lo que hace que el lote sea un lote: sin ordenar, Hibernate corta el batch
        // cada vez que alterna de tabla, y guardar un producto variable alterna constantemente entre
        // variaciones y atributos.
        assertThat(entityManagerFactory.getProperties())
                .containsEntry("hibernate.jdbc.batch_size", "25")
                .containsEntry("hibernate.order_inserts", "true")
                .containsEntry("hibernate.order_updates", "true");
    }
}
