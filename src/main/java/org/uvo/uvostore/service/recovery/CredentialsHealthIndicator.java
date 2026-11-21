package org.uvo.uvostore.service.recovery;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;
import org.uvo.uvostore.config.RecoveryModePolicy;

/**
 * IMP-09. La sonda de descifrado en la readiness ({@code management.endpoint.health.group.readiness.include}): una
 * instalación cuya llave no abre las credenciales de la base no recibe tráfico. Aceptaría pedidos que después no
 * puede cobrar ni despachar.
 *
 * <p>No va en liveness: reiniciar no arregla una llave equivocada (PROD-15a).
 *
 * <p>Con el modo recuperación queda UP aunque falle: ahí la instalación existe precisamente para revisar, y la falla
 * se ve en el informe y en {@code uvostore_credentials_decrypt_failures}.
 *
 * <p>Usa el resultado del arranque y no vuelve a descifrar en cada sonda: la llave no cambia sin reiniciar.
 *
 * <p>Se puede apagar ({@code app.health.credentials.enabled=false}) solo para la suite de pruebas, que corre sobre la
 * base local con otra llave (ver {@code src/test/resources/config/application.properties}); en un despliegue, la
 * readiness la nombra y sin ella la aplicación no arranca.
 */
@Component("credentials")
@ConditionalOnProperty(name = "app.health.credentials.enabled", havingValue = "true", matchIfMissing = true)
public class CredentialsHealthIndicator implements HealthIndicator {

    private final CredentialDecryptProbe probe;
    private final RecoveryModePolicy recoveryMode;

    public CredentialsHealthIndicator(CredentialDecryptProbe probe, RecoveryModePolicy recoveryMode) {
        this.probe = probe;
        this.recoveryMode = recoveryMode;
    }

    @Override
    public Health health() {
        int failures = probe.lastResult().failures();
        if (failures == 0 || recoveryMode.isActive()) {
            return Health.up().withDetail("decryptFailures", failures).build();
        }
        return Health.down().withDetail("decryptFailures", failures).build();
    }
}
