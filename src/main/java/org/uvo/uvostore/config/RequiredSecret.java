package org.uvo.uvostore.config;

import java.nio.charset.StandardCharsets;

// Startup validation for the three configuration secrets of the platform (jwt.secret,
// app.encryption-key, app.platform-api-key). Until C4 all three shipped with working defaults
// committed in application.properties, so forgetting an env var in a deployment left the app
// running happily on publicly-known secrets — forgeable JWTs, decryptable gateway credentials and
// an open /api/platform/**. The defaults are gone; these helpers turn "missing" and "still the
// committed value" into a startup failure whose message names the env var and how to replace it.
//
// Validation lives in each consumer's constructor rather than in one central @PostConstruct: bean
// construction order isn't guaranteed, so a central check could easily run *after* JwtService had
// already failed with jjwt's WeakKeyException, which names neither the property nor the env var.
public final class RequiredSecret {

    // How every one of these is generated. Kept here so the three call sites can't drift apart.
    public static final String GENERATE_HINT = "openssl rand -base64 32";

    // These literals were committed to application.properties, so they live in the repo's git
    // history and must be treated as public knowledge. They are kept here for exactly one reason:
    // so the app can refuse to start if one of them ever shows up in a real environment (someone
    // "configuring" the env var by copying the old value out of the repo).
    public static final String LEGACY_JWT_SECRET = "dev-only-insecure-jwt-secret-change-me-please-32chars-min";
    public static final String LEGACY_PLATFORM_API_KEY = "dev-only-insecure-platform-key-change-me";
    public static final String LEGACY_ENCRYPTION_KEY = "d/S6b0SJRrMoJhWogobxo7fJzLyhQox5Nq709OLTiNI=";

    private RequiredSecret() {
    }

    public static String require(String value, String envVar) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    envVar + " no está configurada y ya no tiene valor por defecto. Defínela en el .env " +
                    "local (ver .env.example) o en el entorno del despliegue. Genera una con: " + GENERATE_HINT);
        }
        return value;
    }

    public static String rejectLegacyDefault(String value, String legacyValue, String envVar) {
        if (legacyValue.equals(value)) {
            throw new IllegalStateException(
                    envVar + " tiene el valor por defecto que estuvo commiteado en application.properties. " +
                    "Ese valor está en el historial de git, es público, y no sirve como secreto. " +
                    "Genera uno nuevo con: " + GENERATE_HINT);
        }
        return value;
    }

    public static String minBytes(String value, int minBytes, String envVar) {
        int actual = value.getBytes(StandardCharsets.UTF_8).length;
        if (actual < minBytes) {
            throw new IllegalStateException(
                    envVar + " es demasiado corta: " + actual + " bytes, se requieren al menos " + minBytes +
                    ". Genera una con: " + GENERATE_HINT);
        }
        return value;
    }
}
