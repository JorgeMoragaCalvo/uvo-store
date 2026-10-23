package org.uvo.uvostore.config;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * PROD-08a. Qué puede hacer en la base el rol con el que se conecta la aplicación ({@code DB_USERNAME}).
 *
 * <p>El §7.2 del plan pide que la aplicación funcione sin permisos de superusuario ni de creación de esquema, y
 * que el DDL lo aplique otro rol, el de Flyway ({@code FLYWAY_USER}). Las tres preguntas cubren las tres formas
 * de tener DDL: ser superusuario, poder crear objetos en el esquema, o ser dueño de una tabla (el dueño puede
 * alterarla o borrarla sin ningún permiso más).
 *
 * @param superuser         el rol es superusuario
 * @param canCreateInSchema el rol puede crear objetos en el esquema actual
 * @param ownsTables        el rol es dueño de alguna tabla del esquema actual
 */
public record DatabaseRole(String name, boolean superuser, boolean canCreateInSchema, boolean ownsTables) {

    private static final String QUERY = """
            SELECT current_user AS name,
                   r.rolsuper AS superuser,
                   has_schema_privilege(current_user, current_schema(), 'CREATE') AS can_create,
                   EXISTS (SELECT 1 FROM pg_tables t
                           WHERE t.schemaname = current_schema() AND t.tableowner = current_user) AS owns_tables
            FROM pg_roles r
            WHERE r.rolname = current_user
            """;

    public static DatabaseRole query(JdbcTemplate jdbc) {
        return jdbc.queryForObject(QUERY, (rs, rowNum) -> new DatabaseRole(rs.getString("name"),
                rs.getBoolean("superuser"), rs.getBoolean("can_create"), rs.getBoolean("owns_tables")));
    }
}
