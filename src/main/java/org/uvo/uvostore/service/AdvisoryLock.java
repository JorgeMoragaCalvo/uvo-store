package org.uvo.uvostore.service;

import io.sentry.Sentry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Un solo ejecutor a la vez para un trabajo programado, aunque haya varias instancias de la app.
 *
 * <p>Nació dentro de {@code PaymentReconciliationService} (G1) y se extrajo en PROD-13a, cuando el
 * barrido de stock ({@code StockRecoveryJob}) necesitó lo mismo. Cada trabajo trae su propia clave: lo
 * único que importa es que no la use ningún otro {@code pg_advisory_lock} de la base.
 */
@Component
public class AdvisoryLock {

    private static final Logger log = LoggerFactory.getLogger(AdvisoryLock.class);

    private final DataSource dataSource;

    public AdvisoryLock(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * Ejecuta {@code work} solo si consigue el cerrojo {@code key}; si lo tiene otra instancia, no hace
     * nada.
     *
     * @param jobName solo para los mensajes de log
     * @return true si el trabajo corrió
     */
    public boolean runExclusively(long key, String jobName, Runnable work) {
        // El cerrojo se pide sobre una conexión propia y se mantiene toda la corrida: pg_advisory_lock
        // es de sesión, así que pedirlo y soltarlo por JdbcTemplate daría dos conexiones distintas del
        // pool y no protegería nada. Con app.storage.driver=s3 ya no vale suponer una sola instancia.
        Connection lockConnection;
        try {
            lockConnection = dataSource.getConnection();
        } catch (SQLException e) {
            log.error("No se pudo abrir conexión para el cerrojo de {}: {}", jobName, e.getMessage());
            Sentry.captureException(e);
            return false;
        }

        try {
            if (!tryLock(lockConnection, key, jobName)) {
                log.debug("Otra instancia está ejecutando {}; esta corrida no hace nada", jobName);
                return false;
            }
            try {
                work.run();
                return true;
            } finally {
                unlock(lockConnection, key, jobName);
            }
        } finally {
            closeQuietly(lockConnection, jobName);
        }
    }

    private boolean tryLock(Connection connection, long key, String jobName) {
        try (PreparedStatement statement = connection.prepareStatement("select pg_try_advisory_lock(?)")) {
            statement.setLong(1, key);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() && rs.getBoolean(1);
            }
        } catch (SQLException e) {
            log.error("No se pudo pedir el cerrojo de {}: {}", jobName, e.getMessage());
            Sentry.captureException(e);
            return false;
        }
    }

    private void unlock(Connection connection, long key, String jobName) {
        try (PreparedStatement statement = connection.prepareStatement("select pg_advisory_unlock(?)")) {
            statement.setLong(1, key);
            statement.execute();
        } catch (SQLException e) {
            // Cerrar la conexión suelta igualmente los cerrojos de sesión, así que esto no deja el
            // trabajo bloqueado para siempre; se registra porque no debería pasar.
            log.warn("No se pudo soltar el cerrojo de {}: {}", jobName, e.getMessage());
        }
    }

    private void closeQuietly(Connection connection, String jobName) {
        try {
            connection.close();
        } catch (SQLException e) {
            log.warn("No se pudo cerrar la conexión del cerrojo de {}: {}", jobName, e.getMessage());
        }
    }
}
