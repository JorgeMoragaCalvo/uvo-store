package org.uvo.uvostore.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.MissingNode;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

/**
 * PROD-18. Las filas de {@code audit_events}, leídas por JDBC. Dentro de la transacción de un test se ven: el insert
 * de la auditoría es inmediato (IDENTITY), así que no hace falta forzar un flush.
 */
public final class AuditRows {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String SELECT = "SELECT id, store_id, actor_type, actor_id, actor_label, action, target_type, "
            + "target_id, result, request_id, details::text AS details FROM audit_events ";

    private AuditRows() {
    }

    public static List<Row> forStore(JdbcTemplate jdbc, Long storeId) {
        return jdbc.query(SELECT + "WHERE store_id = ? ORDER BY id", (rs, n) -> row(rs), storeId);
    }

    public static List<Row> forStore(JdbcTemplate jdbc, Long storeId, String action) {
        return jdbc.query(SELECT + "WHERE store_id = ? AND action = ? ORDER BY id", (rs, n) -> row(rs), storeId, action);
    }

    public static Row single(JdbcTemplate jdbc, Long storeId, String action) {
        List<Row> rows = forStore(jdbc, storeId, action);
        if (rows.size() != 1) {
            throw new AssertionError("Se esperaba una fila " + action + " en la tienda " + storeId + " y hay "
                    + rows.size() + ": " + rows);
        }
        return rows.getFirst();
    }

    private static Row row(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Row(rs.getLong("id"), (Long) rs.getObject("store_id"), rs.getString("actor_type"),
                (Long) rs.getObject("actor_id"), rs.getString("actor_label"), rs.getString("action"),
                rs.getString("target_type"), (Long) rs.getObject("target_id"), rs.getString("result"),
                rs.getString("request_id"), rs.getString("details"));
    }

    public record Row(long id, Long storeId, String actorType, Long actorId, String actorLabel, String action,
                      String targetType, Long targetId, String result, String requestId, String details) {

        /** {@code details} como JSON; un nodo ausente si es nulo. */
        public JsonNode json() {
            if (details == null) {
                return MissingNode.getInstance();
            }
            try {
                return MAPPER.readTree(details);
            } catch (Exception e) {
                throw new IllegalStateException("details no es JSON: " + details, e);
            }
        }

        /** El cambio de un campo: {@code details.changes.<campo>}. */
        public JsonNode change(String field) {
            return json().path("changes").path(field);
        }
    }
}
