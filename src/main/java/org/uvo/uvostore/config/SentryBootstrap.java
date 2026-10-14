package org.uvo.uvostore.config;

import io.sentry.SentryOptions;

import java.util.function.UnaryOperator;

/**
 * PROD-15a. Qué se le entrega al SDK de Sentry al arrancar: el DSN, y desde qué ambiente y versión informa.
 *
 * <p>Sin {@code environment} el SDK usa {@code "production"}, y sin {@code release} no asocia el error a
 * ninguna versión: un fallo de staging aparecía mezclado con los de producción, y nadie podía decir si un
 * error nuevo venía del despliegue de ayer. Las dos se leen aquí de {@code SENTRY_ENVIRONMENT} y
 * {@code SENTRY_RELEASE}, que son los nombres que el propio SDK usa para esto.
 *
 * <p><b>Por qué se leen a mano.</b> El SDK las leería solo si se activara
 * {@code setEnableExternalConfiguration(true)}, pero eso también habilita cualquier otra
 * {@code SENTRY_*} y {@code sentry.properties} que haya en el entorno (tasas de muestreo, etiquetas,
 * proxies…) sin que esta aplicación lo haya decidido. Con el valor por defecto ({@code false}) poner
 * {@code SENTRY_ENVIRONMENT} en el contenedor no hacía nada, y nada avisaba de ello. Leer solo estas tres,
 * explícitamente, mantiene el patrón de {@code SENTRY_DSN}.
 *
 * <p><b>El entorno entra por parámetro</b> para poder probar la configuración sin tocar el entorno real
 * del proceso. {@code main()} pasa {@code System::getenv}: se lee el entorno del sistema y no el
 * {@code Environment} de Spring porque, a esa altura, Spring todavía no existe (y {@code spring-dotenv}
 * solo rellena el segundo; ver {@code .env.example}).
 *
 * <p><b>Lo que esto no impone:</b> si {@code SENTRY_ENVIRONMENT} falta, el SDK sigue diciendo
 * {@code production}. Un staging que se olvide de ponerla informará como producción.
 */
public final class SentryBootstrap {

    static final String ENV_DSN = "SENTRY_DSN";
    static final String ENV_ENVIRONMENT = "SENTRY_ENVIRONMENT";
    static final String ENV_RELEASE = "SENTRY_RELEASE";

    private SentryBootstrap() {
    }

    /** {@code true} si hay DSN. Sin él el SDK no se inicializa y todos los {@code Sentry.capture*} son no-ops. */
    public static boolean isConfigured(UnaryOperator<String> env) {
        return notBlank(env.apply(ENV_DSN)) != null;
    }

    /** Un valor en blanco no se fija: se deja el que el SDK ya tiene, en vez de enviar una etiqueta vacía. */
    public static void configure(SentryOptions options, UnaryOperator<String> env) {
        String dsn = notBlank(env.apply(ENV_DSN));
        if (dsn != null) {
            options.setDsn(dsn);
        }
        String environment = notBlank(env.apply(ENV_ENVIRONMENT));
        if (environment != null) {
            options.setEnvironment(environment);
        }
        String release = notBlank(env.apply(ENV_RELEASE));
        if (release != null) {
            options.setRelease(release);
        }
    }

    private static String notBlank(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
