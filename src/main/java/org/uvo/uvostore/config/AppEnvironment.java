package org.uvo.uvostore.config;

import java.util.Arrays;
import java.util.Locale;

/**
 * PROD-08a. Dónde corre esta instalación, leído de {@code app.environment} ({@code APP_ENVIRONMENT}).
 *
 * <p>Es la señal que enciende el modo estricto de {@link ProductionConfigurationRules}. Tiene que ser
 * <b>explícita</b>: el §7.1 del plan avisa que activar un perfil {@code prod} no implementa nada por sí solo, y
 * adivinar el ambiente a partir de otros valores es justo lo que deja pasar un despliegue mal configurado.
 *
 * <p><b>Un valor desconocido impide el arranque.</b> Si {@code prod} o {@code produccion} cayeran a
 * {@code development}, una errata apagaría en silencio todas las comprobaciones, que es el fallo que esta
 * clase existe para evitar.
 */
public enum AppEnvironment {

    /** El valor por defecto. No comprueba nada: es lo que usan el desarrollo local y la suite de tests. */
    DEVELOPMENT,
    /** Las mismas reglas que producción, salvo que permite el sandbox de Webpay. */
    STAGING,
    PRODUCTION;

    public static AppEnvironment parse(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) {
            return DEVELOPMENT;
        }
        return Arrays.stream(values())
                .filter(environment -> environment.label().equals(normalized))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("APP_ENVIRONMENT tiene un valor desconocido: '" + value
                        + "'. Valores válidos: development, staging, production. Un valor mal escrito no puede "
                        + "caer a development, porque apagaría en silencio las comprobaciones de producción."));
    }

    /** Como se escribe en {@code APP_ENVIRONMENT} y en {@code SENTRY_ENVIRONMENT}. */
    public String label() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** Si se aplican las comprobaciones de producción. */
    public boolean isStrict() {
        return this != DEVELOPMENT;
    }
}
