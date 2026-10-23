package org.uvo.uvostore.config;

import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.flywaydb.core.internal.jdbc.DriverDataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PROD-08a. Flyway migra con {@code FLYWAY_USER} cuando está puesto, y con el pool de la aplicación cuando no.
 */
class FlywayMigrationRoleTest {

    private static final String URL = "jdbc:postgresql://localhost:5432/uvostore?charSet=UTF8";

    @Test
    @DisplayName("Con FLYWAY_USER, Flyway se conecta con ese rol a la misma base que la aplicación")
    void withAUserFlywayConnectsAsThatRole() {
        FluentConfiguration configuration = new FluentConfiguration();

        new FlywayMigrationRole(URL, " uvostore_migrador ", "secreto").customize(configuration);

        assertThat(configuration.getDataSource()).isInstanceOfSatisfying(DriverDataSource.class, dataSource -> {
            assertThat(dataSource.getUser()).isEqualTo("uvostore_migrador");
            assertThat(dataSource.getUrl()).isEqualTo(URL);
        });
    }

    @Test
    @DisplayName("Sin FLYWAY_USER no se toca nada")
    void withoutAUserNothingChanges() {
        FluentConfiguration configuration = new FluentConfiguration();

        new FlywayMigrationRole(URL, "", "").customize(configuration);

        assertThat(configuration.getDataSource()).isNull();
    }

    /**
     * Que Boot de verdad aplica el customizer, y después de fijar su propio datasource: si una subida de versión
     * cambiara ese orden, el rol de Flyway se ignoraría en silencio. Se usa el mismo rol de la base local para
     * que las migraciones validen igual.
     */
    @Nested
    @SpringBootTest(properties = {
            "app.flyway.user=${spring.datasource.username}",
            "app.flyway.password=${spring.datasource.password:}"
    })
    class InTheApplication {

        @Autowired
        private Flyway flyway;
        @Value("${spring.datasource.username}")
        private String username;

        @Test
        @DisplayName("Flyway usa su propia conexión, no el pool de Hikari de la aplicación")
        void flywayDoesNotUseTheApplicationPool() {
            assertThat(flyway.getConfiguration().getDataSource())
                    .isNotInstanceOf(HikariDataSource.class)
                    .isInstanceOfSatisfying(DriverDataSource.class,
                            dataSource -> assertThat(dataSource.getUser()).isEqualTo(username));
        }
    }
}
