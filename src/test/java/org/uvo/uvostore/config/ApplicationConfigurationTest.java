package org.uvo.uvostore.config;

import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.task.TaskSchedulingProperties;
import org.springframework.boot.web.server.autoconfigure.ServerProperties;
import org.springframework.boot.web.server.Shutdown;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.uvo.uvostore.support.IntegrationTestSupport;

import java.time.Duration;
import java.util.Properties;

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
    @DisplayName("El planificador tiene más de un hilo para sus cinco trabajos")
    void theSchedulerIsNotASingleThread() {
        // R1. El default de Spring es 1, y hay cinco @Scheduled: PaymentReconciliationService,
        // AbandonedOrderJob, StockRecoveryJob y los dos canales del outbox (OutboxScheduler, PROD-13b). Con
        // un solo hilo, el que se quedara esperando a una pasarela lenta o a un POS que no contesta dejaba a
        // los demás sin correr, sin error y sin log.
        assertThat(taskSchedulingProperties.getPool().getSize()).isGreaterThan(1);
    }

    @Test
    @DisplayName("Al apagar, el planificador espera a lo que está en vuelo")
    void theSchedulerWaitsForWorkInFlightOnShutdown() {
        // PROD-13b. Por defecto Spring no espera: una venta al POS cortada a medias por un redespliegue
        // queda huérfana, y una huérfana del POS es revisión manual (no se sabe si llegó). El plazo tiene
        // que cubrir una llamada entera: 5s de conexión + 20s de lectura (app.pos.*-timeout-ms).
        assertThat(taskSchedulingProperties.getShutdown().isAwaitTermination()).isTrue();
        assertThat(taskSchedulingProperties.getShutdown().getAwaitTerminationPeriod())
                .isGreaterThanOrEqualTo(Duration.ofSeconds(25));
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
