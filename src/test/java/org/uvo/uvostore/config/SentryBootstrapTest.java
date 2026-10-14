package org.uvo.uvostore.config;

import io.sentry.SentryOptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.function.UnaryOperator;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PROD-15a. Sentry informa desde qué ambiente y qué versión.
 *
 * <p>Es una prueba sin Spring a propósito: {@code main()} corre antes de que exista el contexto y lee el
 * entorno real del proceso, así que lo único comprobable de forma aislada es esta clase, a la que el
 * entorno entra por parámetro. Lo que queda sin cubrir es la línea de {@code UvoStoreApplication.main()}
 * que la llama; por eso esa línea es la única que queda allí.
 */
class SentryBootstrapTest {

    private static UnaryOperator<String> env(String... keyValues) {
        Map<String, String> map = new HashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put(keyValues[i], keyValues[i + 1]);
        }
        return map::get;
    }

    @Test
    @DisplayName("El ambiente y la versión salen de SENTRY_ENVIRONMENT y SENTRY_RELEASE")
    void environmentAndReleaseAreTakenFromTheEnvironment() {
        SentryOptions options = new SentryOptions();

        SentryBootstrap.configure(options, env(
                "SENTRY_DSN", "https://clave@sentry.example/1",
                "SENTRY_ENVIRONMENT", "staging",
                "SENTRY_RELEASE", "v1.2.3"));

        assertThat(options.getDsn()).isEqualTo("https://clave@sentry.example/1");
        assertThat(options.getEnvironment()).isEqualTo("staging");
        assertThat(options.getRelease()).isEqualTo("v1.2.3");
    }

    @Test
    @DisplayName("Sin SENTRY_ENVIRONMENT ni SENTRY_RELEASE no se inventa ninguno: queda lo que el SDK trae")
    void missingOrBlankLabelsAreLeftAlone() {
        SentryOptions options = new SentryOptions();

        SentryBootstrap.configure(options, env(
                "SENTRY_DSN", "https://clave@sentry.example/1",
                "SENTRY_ENVIRONMENT", "   ",
                "SENTRY_RELEASE", ""));

        assertThat(options.getRelease()).isNull();
        // El valor por defecto del SDK, que es justo lo que un staging sin configurar acabaría usando.
        assertThat(options.getEnvironment()).isEqualTo(new SentryOptions().getEnvironment());
    }

    @Test
    @DisplayName("Los espacios alrededor de un valor no entran en la etiqueta")
    void valuesAreTrimmed() {
        SentryOptions options = new SentryOptions();

        SentryBootstrap.configure(options, env(
                "SENTRY_DSN", "https://clave@sentry.example/1",
                "SENTRY_ENVIRONMENT", " production\n",
                "SENTRY_RELEASE", " v1.2.3 "));

        assertThat(options.getEnvironment()).isEqualTo("production");
        assertThat(options.getRelease()).isEqualTo("v1.2.3");
    }

    @Test
    @DisplayName("Solo hay Sentry si hay DSN: sin él (o en blanco) el SDK no se inicializa")
    void sentryIsOnlyConfiguredWithADsn() {
        assertThat(SentryBootstrap.isConfigured(env())).isFalse();
        assertThat(SentryBootstrap.isConfigured(env("SENTRY_DSN", ""))).isFalse();
        assertThat(SentryBootstrap.isConfigured(env("SENTRY_DSN", "  "))).isFalse();
        assertThat(SentryBootstrap.isConfigured(env("SENTRY_DSN", "https://clave@sentry.example/1"))).isTrue();
    }
}
