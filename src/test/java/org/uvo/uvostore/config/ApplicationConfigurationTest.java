package org.uvo.uvostore.config;

import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.web.server.autoconfigure.ServerProperties;
import org.springframework.boot.web.server.Shutdown;
import org.uvo.uvostore.support.IntegrationTestSupport;

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
