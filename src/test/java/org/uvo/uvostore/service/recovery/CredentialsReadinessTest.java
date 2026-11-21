package org.uvo.uvostore.service.recovery;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Status;
import org.uvo.uvostore.config.RecoveryModePolicy;

import java.io.InputStream;
import java.util.Map;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * IMP-09. La sonda de descifrado decide la readiness: una llave que no abre las credenciales deja la instancia fuera
 * del balanceador, salvo en modo recuperación, que existe justo para revisar.
 */
class CredentialsReadinessTest {

    @Test
    @DisplayName("La readiness de la aplicación incluye la sonda; la liveness no")
    void readinessGroupIncludesTheProbe() throws Exception {
        Properties main = new Properties();
        // El application.properties principal (la suite pisa la readiness en config/, ver ese archivo).
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("application.properties")) {
            main.load(in);
        }
        assertThat(main.getProperty("management.endpoint.health.group.readiness.include")).contains("credentials");
        assertThat(main.getProperty("management.endpoint.health.group.liveness.include")).doesNotContain("credentials");
    }

    @Test
    @DisplayName("Con fallas está DOWN; sin fallas, o en modo recuperación, UP")
    void downOnlyWithFailuresOutsideRecovery() {
        assertThat(indicator(0, false).health().getStatus()).isEqualTo(Status.UP);
        assertThat(indicator(2, false).health().getStatus()).isEqualTo(Status.DOWN);
        assertThat(indicator(2, true).health().getStatus()).isEqualTo(Status.UP);
    }

    private static CredentialsHealthIndicator indicator(int failures, boolean recovery) {
        CredentialDecryptProbe probe = mock(CredentialDecryptProbe.class);
        when(probe.lastResult()).thenReturn(new CredentialDecryptProbe.Result(Map.of(), failures));
        return new CredentialsHealthIndicator(probe, new RecoveryModePolicy(recovery));
    }
}
