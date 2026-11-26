package org.uvo.uvostore.service.recovery;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.uvo.uvostore.config.RecoveryModePolicy;
import org.uvo.uvostore.service.settings.SecretCrypto;

/**
 * IMP-09. ¿Abre {@code APP_ENCRYPTION_KEY} las credenciales que hay en la base?
 *
 * <p>{@code EncryptionKeyHolder} solo comprueba que la llave tenga forma de llave (base64, 32 bytes). Una llave
 * equivocada pero bien formada —la de otro ambiente, una copia vieja del gestor de contraseñas— arranca igual, y el
 * error aparece recién cuando alguien paga ({@code "No se pudieron descifrar…"}). Después de restaurar un respaldo,
 * que la llave recuperada sea la correcta es justo lo primero que hay que saber, y no se puede esperar a un cobro.
 *
 * <p>Descifra cada valor cifrado de la base, sin entidades ni convertidores (un convertidor que lanza al cargar una
 * entidad tumba la consulta entera): las credenciales de pasarela, de transportista, del POS y los secretos de Stripe
 * de los ajustes. Nunca registra un valor, ni descifrado ni cifrado: solo cuenta.
 *
 * <p>Corre al arrancar. El resultado se publica como {@code uvostore_credentials_decrypt_failures}, en el log, en el
 * informe de recuperación y en la sonda de readiness ({@link CredentialsHealthIndicator}).
 */
@Component
public class CredentialDecryptProbe {

    private static final Logger log = LoggerFactory.getLogger(CredentialDecryptProbe.class);

    /** Basta para cualquier instalación real; el tope solo evita que una tabla enorme retrase el arranque. */
    private static final int MAX_ROWS_PER_SOURCE = 5_000;

    /** Cada fuente: un nombre para el informe y la consulta que devuelve sus valores cifrados no vacíos. */
    private static final Map<String, String> SOURCES = sources();

    private final JdbcTemplate jdbcTemplate;
    private final RecoveryModePolicy recoveryMode;
    private final AtomicReference<Result> last = new AtomicReference<>();

    public CredentialDecryptProbe(JdbcTemplate jdbcTemplate, RecoveryModePolicy recoveryMode, MeterRegistry registry) {
        this.jdbcTemplate = jdbcTemplate;
        this.recoveryMode = recoveryMode;
        Gauge.builder("uvostore_credentials_decrypt_failures", last, ref -> ref.get() == null ? 0 : ref.get().failures())
                .description("Valores cifrados de la base que APP_ENCRYPTION_KEY no puede abrir (IMP-09)")
                .register(registry);
        Gauge.builder("uvostore_recovery_mode", () -> recoveryMode.isActive() ? 1 : 0)
                .description("1 si la instalación arrancó en modo recuperación (IMP-09)")
                .register(registry);
    }

    /** El resultado por fuente. {@code failures} es la suma de las fallas de todas. */
    public record Result(Map<String, SourceResult> sources, int failures) {
    }

    public record SourceResult(int checked, int failed) {
    }

    @EventListener(ApplicationReadyEvent.class)
    public void probeOnStartup() {
        Result result = probe();
        if (recoveryMode.isActive()) {
            log.warn("Modo recuperación ACTIVO: trabajos programados detenidos; POS, correo, checkout y webhooks "
                    + "cortados. Conciliar con /api/platform/recovery/report antes de salir (docs/recuperacion.md).");
        }
        if (result.failures() > 0) {
            log.error("APP_ENCRYPTION_KEY no abre {} valores cifrados de la base ({}). Es la llave equivocada o los "
                    + "valores están dañados: no se puede cobrar, despachar ni hablar con el POS con esas credenciales.",
                    result.failures(), result.sources());
        } else {
            log.info("Sonda de descifrado: APP_ENCRYPTION_KEY abre todos los valores cifrados ({})", result.sources());
        }
    }

    public Result probe() {
        Map<String, SourceResult> bySource = new LinkedHashMap<>();
        int failures = 0;
        for (Map.Entry<String, String> source : SOURCES.entrySet()) {
            List<String> values = jdbcTemplate.queryForList(source.getValue(), String.class, MAX_ROWS_PER_SOURCE);
            int failed = 0;
            for (String value : values) {
                if (!opens(value)) {
                    failed++;
                }
            }
            bySource.put(source.getKey(), new SourceResult(values.size(), failed));
            failures += failed;
        }
        Result result = new Result(bySource, failures);
        last.set(result);
        return result;
    }

    /** El último resultado; la sonda corre si todavía no corrió (una prueba que no levanta el evento de arranque). */
    public Result lastResult() {
        Result result = last.get();
        return result != null ? result : probe();
    }

    private static boolean opens(String value) {
        try {
            SecretCrypto.decrypt(value);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static Map<String, String> sources() {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("payment_gateway_configs.credentials",
                "select credentials from payment_gateway_configs where credentials is not null and credentials <> '' limit ?");
        sources.put("shipping_methods.api_credentials",
                "select api_credentials from shipping_methods where api_credentials is not null and api_credentials <> '' limit ?");
        // IMP-08 (V38): los secretos del POS pasaron de pos_connections a pos_connection_keys, una fila por llave.
        sources.put("pos_connection_keys.secret",
                "select secret from pos_connection_keys where secret is not null and secret <> '' limit ?");
        sources.put("settings.stripe",
                "select value from settings where key in ('stripe_secret_key', 'stripe_webhook_secret') "
                        + "and value is not null and value <> '' limit ?");
        return sources;
    }
}
