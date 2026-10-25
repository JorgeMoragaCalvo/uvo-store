package org.uvo.uvostore.audit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.uvo.uvostore.config.RequestIdFilter;
import org.uvo.uvostore.entity.audit.enums.AuditAction;
import org.uvo.uvostore.entity.audit.enums.AuditTargetType;
import org.uvo.uvostore.service.audit.AuditEntry;
import org.uvo.uvostore.service.audit.AuditTrail;
import org.uvo.uvostore.support.AuditRows;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PROD-18. Lo que solo se ve con transacciones confirmadas de verdad: que la fila vive y muere con la transacción del
 * cambio, y que la base no deja corregirla.
 *
 * <p>Sin {@code IntegrationTestSupport} (que deshace todo) y con la misma configuración, para compartir su contexto.
 * Las filas llevan una tienda inventada y negativa, que no choca con nada, y {@link #cleanUp} las borra.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AuditTrailTransactionTest {

    @Autowired
    private AuditTrail auditTrail;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private JdbcTemplate jdbc;

    private final long store = -(System.nanoTime() % 1_000_000_000L) - 1;

    @AfterEach
    void cleanUp() {
        MDC.remove(RequestIdFilter.MDC_KEY);
        jdbc.update("DELETE FROM audit_events WHERE store_id = ?", store);
    }

    @Test
    @DisplayName("Fuera de una transacción no escribe: la fila tiene que ir en la del cambio")
    void refusesToWriteOutsideATransaction() {
        assertThatThrownBy(() -> auditTrail.record(entry())).isInstanceOf(IllegalTransactionStateException.class);

        assertThat(rows()).isEmpty();
    }

    @Test
    @DisplayName("Si el cambio se deshace, su fila también")
    void rollsBackWithTheChange() {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            auditTrail.record(entry());
            status.setRollbackOnly();
        });

        assertThat(rows()).isEmpty();
    }

    @Test
    @DisplayName("Si el cambio confirma, queda la fila: sin nadie autenticado es el sistema, con el id de la petición")
    void commitsWithTheChange() {
        MDC.put(RequestIdFilter.MDC_KEY, "prueba-0001-transaccion");

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> auditTrail.record(entry()));

        List<AuditRows.Row> rows = rows();
        assertThat(rows).hasSize(1);
        AuditRows.Row row = rows.getFirst();
        assertThat(row.actorType()).isEqualTo("SYSTEM");
        assertThat(row.actorId()).isNull();
        assertThat(row.result()).isEqualTo("SUCCESS");
        assertThat(row.requestId()).isEqualTo("prueba-0001-transaccion");
    }

    @Test
    @DisplayName("Una fila no se corrige: la base rechaza el UPDATE, venga de donde venga; el DELETE sí pasa")
    void rowsCannotBeUpdatedButCanBeDeleted() {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> auditTrail.record(entry()));

        assertThatThrownBy(() -> jdbc.update("UPDATE audit_events SET actor_label = 'otro' WHERE store_id = ?", store))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("solo inserción");
        assertThat(jdbc.update("DELETE FROM audit_events WHERE store_id = ?", store)).isEqualTo(1);
    }

    private AuditEntry entry() {
        return AuditEntry.of(AuditAction.STORE_STATUS_UPDATE, AuditTargetType.STORE, store).forStore(store);
    }

    private List<AuditRows.Row> rows() {
        return AuditRows.forStore(jdbc, store);
    }
}
