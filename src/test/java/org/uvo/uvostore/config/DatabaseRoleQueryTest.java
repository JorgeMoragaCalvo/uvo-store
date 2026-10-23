package org.uvo.uvostore.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.uvo.uvostore.support.IntegrationTestSupport;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PROD-08a. La consulta de {@link DatabaseRole}, contra la base de verdad: el resto de los tests de la regla usa
 * un rol fijo y no la puede cubrir.
 *
 * <p>La base local y la de CI se conectan con {@code uvostore}, que es superusuario y dueño de todo lo que migró
 * Flyway: justo lo que producción tiene prohibido, y por eso sirve de caso.
 */
class DatabaseRoleQueryTest extends IntegrationTestSupport {

    @Autowired
    private JdbcTemplate jdbc;
    @Value("${spring.datasource.username}")
    private String username;

    @Test
    @DisplayName("El rol local es superusuario, crea en el esquema y es dueño de las tablas, y la consulta lo ve")
    void theLocalRoleIsSeenAsWhatItIs() {
        DatabaseRole role = DatabaseRole.query(jdbc);

        assertThat(role.name()).isEqualTo(username);
        assertThat(role.superuser()).isTrue();
        assertThat(role.canCreateInSchema()).isTrue();
        assertThat(role.ownsTables()).isTrue();
    }
}
