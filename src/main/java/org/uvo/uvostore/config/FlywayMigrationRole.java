package org.uvo.uvostore.config;

import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.flyway.autoconfigure.FlywayConfigurationCustomizer;
import org.springframework.stereotype.Component;

/**
 * PROD-08a. Flyway migra con su propio rol ({@code FLYWAY_USER}/{@code FLYWAY_PASSWORD}), para que el de la
 * aplicación ({@code DB_USERNAME}) no necesite permisos de DDL (§7.2 del plan).
 *
 * <p>Sin {@code FLYWAY_USER} no se toca nada y Flyway sigue usando el pool de la aplicación, como siempre: es lo
 * que hace el desarrollo local, donde {@code uvostore} es dueño de todo.
 *
 * <p><b>Por qué no basta {@code spring.flyway.user=${FLYWAY_USER:}}</b> en {@code application.properties}, que
 * Boot ya entiende. {@code FlywayAutoConfiguration.getMigrationDataSource} solo pregunta si el usuario es
 * {@code null}: un placeholder vacío da {@code ""}, que no es nulo, y Boot arma un datasource aparte con usuario
 * vacío que no conecta. Este customizer corre después de que Boot fija el datasource, así que el suyo gana, y un
 * valor vacío no hace nada.
 */
@Component
public class FlywayMigrationRole implements FlywayConfigurationCustomizer {

    private static final Logger log = LoggerFactory.getLogger(FlywayMigrationRole.class);

    private final String url;
    private final String user;
    private final String password;

    public FlywayMigrationRole(@Value("${spring.datasource.url}") String url,
                               @Value("${app.flyway.user:}") String user,
                               @Value("${app.flyway.password:}") String password) {
        this.url = url;
        this.user = user == null ? "" : user.trim();
        this.password = password;
    }

    @Override
    public void customize(FluentConfiguration configuration) {
        if (user.isEmpty()) {
            return;
        }
        log.info("Flyway migra con el rol '{}', distinto del de la aplicación.", user);
        configuration.dataSource(url, user, password);
    }
}
