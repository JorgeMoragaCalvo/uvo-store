package org.uvo.uvostore.service.pos;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * El token de la API de UvoPOS: {@code uvp_<empresa>_<clave>}. El mismo token autentica en los dos sentidos: al
 * POS cuando llama a {@code /api/sync/**} ({@code PosApiKeyAuthFilter}) y a nosotros cuando llamamos al POS
 * ({@code PosClient}).
 *
 * <p>El formato viene del {@code SettingsForm} del Laravel original. POS-6 lo junta aquí porque ahora hay dos sitios
 * que lo leen: los ajustes, donde el administrador lo teclea, y el alta desde el POS, donde lo genera el POS.
 */
public final class PosApiKey {

    private static final Pattern FORMAT = Pattern.compile("^uvp_(\\d+)_([A-Za-z0-9]+)$");

    private PosApiKey() {
    }

    /** La empresa que declara el token, o vacío si no tiene el formato. */
    public static Optional<Long> companyIdOf(String token) {
        Matcher matcher = token == null ? null : FORMAT.matcher(token);
        if (matcher == null || !matcher.matches()) {
            return Optional.empty();
        }
        try {
            return Optional.of(Long.valueOf(matcher.group(1)));
        } catch (NumberFormatException e) {
            // Más dígitos de los que caben en un long: no es una empresa.
            return Optional.empty();
        }
    }

    /** Cuántos caracteres tiene la clave, la parte que va después de la empresa; 0 si no tiene el formato. */
    public static int secretLength(String token) {
        Matcher matcher = token == null ? null : FORMAT.matcher(token);
        return matcher != null && matcher.matches() ? matcher.group(2).length() : 0;
    }
}
