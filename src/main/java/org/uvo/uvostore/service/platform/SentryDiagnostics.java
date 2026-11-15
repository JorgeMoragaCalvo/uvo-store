package org.uvo.uvostore.service.platform;

import io.sentry.Sentry;
import io.sentry.SentryLevel;
import io.sentry.protocol.SentryId;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * IMP-03. Manda a Sentry un evento de prueba, para comprobar en el ambiente desplegado que los errores del servidor
 * llegan, y con qué ambiente y versión. Separado del controlador para que una prueba no envíe nada real.
 */
@Component
public class SentryDiagnostics {

    static final String TEST_MESSAGE = "Evento de prueba IMP-03: comprobación de Sentry del servidor";

    /** El id del evento, o vacío si Sentry no está inicializado (sin {@code SENTRY_DSN}). */
    public Optional<String> sendTestEvent() {
        if (!Sentry.isEnabled()) {
            return Optional.empty();
        }
        SentryId id = Sentry.captureMessage(TEST_MESSAGE, SentryLevel.WARNING);
        return Optional.of(id.toString());
    }
}
