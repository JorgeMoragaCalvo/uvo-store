package org.uvo.uvostore.controller.platform;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.uvo.uvostore.config.RecoveryModePolicy;
import org.uvo.uvostore.controller.advice.ApiError;
import org.uvo.uvostore.service.recovery.RecoveryReportService;

/**
 * IMP-09. La conciliación después de restaurar un respaldo (docs/recuperacion.md). Solo existe en modo recuperación
 * ({@code APP_RECOVERY_MODE=true}); fuera de él responde 409.
 *
 * <ul>
 *   <li>{@code GET /report?from=…}: la base restaurada al lado de lo que dicen las pasarelas, por tienda. Solo lee.</li>
 *   <li>{@code POST /outbox/hold}: deja como fallido, para revisión, lo que el respaldo traía en cola o en vuelo.</li>
 * </ul>
 */
@io.swagger.v3.oas.annotations.tags.Tag(name = "Recuperación (plataforma)", description = "Informe de conciliación y retención del outbox después de restaurar un respaldo; solo en modo recuperación; header X-Platform-Key, solo desde la red privada")
@io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "platformApiKey")
@RestController
@RequestMapping("/api/platform/recovery")
public class PlatformRecoveryController {

    private final RecoveryModePolicy recoveryMode;
    private final RecoveryReportService reportService;

    public PlatformRecoveryController(RecoveryModePolicy recoveryMode, RecoveryReportService reportService) {
        this.recoveryMode = recoveryMode;
        this.reportService = reportService;
    }

    /**
     * @param from desde cuándo comparar las órdenes y reembolsos que se movieron (ISO-8601, p. ej. el punto de
     *             restauración menos los días en que se pudo devolver algo). Los pendientes van siempre, de cualquier
     *             fecha.
     */
    @GetMapping("/report")
    public ResponseEntity<?> report(@RequestParam Instant from) {
        if (!recoveryMode.isActive()) {
            return notInRecoveryMode();
        }
        return ResponseEntity.ok(reportService.report(from));
    }

    public record HoldRequest(@NotNull Instant restoredAt) {
    }

    /** Retiene las filas en cola o en vuelo creadas hasta {@code restoredAt}, el punto de restauración. */
    @PostMapping("/outbox/hold")
    public ResponseEntity<?> hold(@Valid @RequestBody HoldRequest request) {
        if (!recoveryMode.isActive()) {
            return notInRecoveryMode();
        }
        return ResponseEntity.ok(Map.of("held", reportService.holdRestoredOutbox(request.restoredAt())));
    }

    private static ResponseEntity<ApiError> notInRecoveryMode() {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiError.of(HttpStatus.CONFLICT.value(), "Conflict",
                "Solo en modo recuperación (APP_RECOVERY_MODE=true): fuera de él, la conciliación y el outbox cambian "
                        + "lo que el informe describe."));
    }
}
