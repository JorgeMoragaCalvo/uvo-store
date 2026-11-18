package org.uvo.uvostore.controller.platform;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.uvo.uvostore.service.outbox.OutboxAdminService;
import org.uvo.uvostore.service.outbox.OutboxFailureDto;

/**
 * IMP-06. Los correos de acceso (invitaciones al panel y restablecimiento de contraseña) que fallaron de forma
 * definitiva, de cualquier tienda. Es la otra mitad de «Envíos fallidos» del panel: el dueño de una tienda nueva cuya
 * invitación no salió no tiene a nadie que pueda entrar a verla.
 *
 * <p>Solo los tipos de acceso: una fila de orden da 404 aquí, y se ve desde el panel de su tienda. El mismo acceso que el
 * resto de {@code /api/platform/**} ({@code PlatformApiKeyAuthFilter}); las dos acciones quedan en la auditoría de la
 * tienda de la fila.
 */
@io.swagger.v3.oas.annotations.tags.Tag(name = "Correos de acceso (plataforma)", description = "Invitaciones y restablecimientos de contraseña que no se pudieron entregar, de cualquier tienda; header X-Platform-Key, solo desde la red privada")
@io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "platformApiKey")
@RestController
@RequestMapping("/api/platform/access-emails")
public class PlatformAccessEmailController {

    private static final int PAGE_SIZE = 20;

    private final OutboxAdminService outboxAdminService;

    public PlatformAccessEmailController(OutboxAdminService outboxAdminService) {
        this.outboxAdminService = outboxAdminService;
    }

    @GetMapping("/failed")
    public Page<OutboxFailureDto> failed(@RequestParam(required = false) Long storeId,
                                         @RequestParam(defaultValue = "1") int page) {
        return outboxAdminService.listFailedAccessEmails(storeId, PageRequest.of(Math.max(page - 1, 0), PAGE_SIZE));
    }

    /**
     * Vuelve a la cola. Al entregarse se comprueba otra vez que el token siga vigente y sea el actual; si no, la fila
     * queda omitida y lo que corresponde es la recuperación de acceso ({@code POST /api/platform/stores/{id}/owner/
     * access-recovery}) o el reenvío desde el panel, que emiten uno nuevo.
     */
    @PostMapping("/{id}/retry")
    public ResponseEntity<Void> retry(@PathVariable Long id, @RequestBody AccessEmailNoteRequest request) {
        outboxAdminService.retryAccessEmail(id, request.note());
        return ResponseEntity.noContent().build();
    }

    /** Se cierra sin reenviar, porque ya se resolvió por otra vía. */
    @PostMapping("/{id}/resolve")
    public ResponseEntity<Void> resolve(@PathVariable Long id, @RequestBody AccessEmailNoteRequest request) {
        outboxAdminService.resolveAccessEmail(id, request.note());
        return ResponseEntity.noContent().build();
    }
}
