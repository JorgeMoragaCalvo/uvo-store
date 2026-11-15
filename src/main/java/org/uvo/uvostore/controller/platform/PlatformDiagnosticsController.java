package org.uvo.uvostore.controller.platform;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.uvo.uvostore.service.platform.SentryDiagnostics;

import java.util.Map;

/**
 * IMP-03. Comprobaciones de operación sobre el ambiente desplegado. Hoy, una sola: que un error del servidor llega a
 * Sentry con el ambiente y la versión correctos ({@code docs/observabilidad.md}).
 *
 * <p>El mismo acceso que el resto de {@code /api/platform/**}: red privada y {@code X-Platform-Key}
 * ({@code PlatformApiKeyAuthFilter}). No toca datos de ninguna tienda.
 */
@io.swagger.v3.oas.annotations.tags.Tag(name = "Diagnóstico (plataforma)", description = "Comprobaciones de operación del ambiente desplegado; header X-Platform-Key, solo desde la red privada")
@io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "platformApiKey")
@RestController
@RequestMapping("/api/platform/diagnostics")
public class PlatformDiagnosticsController {

    private final SentryDiagnostics sentryDiagnostics;

    public PlatformDiagnosticsController(SentryDiagnostics sentryDiagnostics) {
        this.sentryDiagnostics = sentryDiagnostics;
    }

    /** 202 con el id del evento para buscarlo en Sentry; 409 si el proceso no tiene {@code SENTRY_DSN}. */
    @PostMapping("/sentry-test")
    public ResponseEntity<Map<String, String>> sentryTest() {
        return sentryDiagnostics.sendTestEvent()
                .map(id -> ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of("eventId", id)))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("message",
                        "Sentry no está configurado en este proceso: falta SENTRY_DSN como variable de entorno.")));
    }
}
