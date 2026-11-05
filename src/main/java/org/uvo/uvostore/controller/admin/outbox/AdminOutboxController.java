package org.uvo.uvostore.controller.admin.outbox;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
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
 * GAP-04. Las entregas del outbox (venta al POS, correos de la orden) que fallaron de forma definitiva. Los permisos
 * son los de las órdenes: cada fila es un efecto de una orden y la nota queda en su historial.
 */
@io.swagger.v3.oas.annotations.tags.Tag(name = "Envíos fallidos (admin)", description = "Entregas del outbox que esperan revisión, JWT bearer con rol ADMIN")
@io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/api/admin/outbox")
public class AdminOutboxController {

    private final OutboxAdminService outboxAdminService;

    public AdminOutboxController(OutboxAdminService outboxAdminService) {
        this.outboxAdminService = outboxAdminService;
    }

    @GetMapping("/failed")
    @PreAuthorize("hasAuthority('orders.view')")
    public Page<OutboxFailureDto> failed(@RequestParam(defaultValue = "1") int page) {
        return outboxAdminService.listFailed(PageRequest.of(Math.max(page - 1, 0), 20));
    }

    /** Vuelve a la cola; la entrega el despachador en su siguiente pasada. Auditado. */
    @PostMapping("/{id}/retry")
    @PreAuthorize("hasAuthority('orders.manage')")
    public ResponseEntity<Void> retry(@PathVariable Long id, @RequestBody OutboxRetryRequest request) {
        outboxAdminService.retry(id, request.note(), Boolean.TRUE.equals(request.posChecked()));
        return ResponseEntity.noContent().build();
    }

    /** Se cierra sin reenviar, porque ya se resolvió por otra vía. Auditado. */
    @PostMapping("/{id}/resolve")
    @PreAuthorize("hasAuthority('orders.manage')")
    public ResponseEntity<Void> resolve(@PathVariable Long id, @RequestBody OutboxResolveRequest request) {
        outboxAdminService.resolve(id, request.note());
        return ResponseEntity.noContent().build();
    }
}
