package org.uvo.uvostore.controller.platform;

import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.uvo.uvostore.entity.audit.enums.AuditAction;
import org.uvo.uvostore.entity.tenant.enums.StoreStatus;
import org.uvo.uvostore.service.platform.PlatformAuditEventDto;
import org.uvo.uvostore.service.platform.PlatformStoreDto;
import org.uvo.uvostore.service.platform.PlatformStoreQueryService;
import org.uvo.uvostore.service.platform.StoreClosureService;
import org.uvo.uvostore.service.platform.StoreLifecycleService;

import java.time.Instant;
import java.util.Set;

/**
 * PROD-23. La operación de las tiendas que ya existen: listarlas, suspenderlas y reactivarlas, devolverle el acceso al
 * dueño, cerrarlas y leer su auditoría. El alta y el dominio siguen en {@link StoreOnboardingController}.
 *
 * <p>El mismo acceso que el alta: {@code PlatformApiKeyAuthFilter} (red privada, {@code X-Platform-Key} y el
 * {@code X-Platform-Operator} opcional, que queda como actor en la auditoría). El consumidor es el backoffice de
 * UvoPOS: no hay pantalla propia.
 */
@io.swagger.v3.oas.annotations.tags.Tag(name = "Operación de tiendas (plataforma)", description = "Lista, suspensión, cierre, acceso del dueño y auditoría de las tiendas; header X-Platform-Key, solo desde la red privada")
@io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "platformApiKey")
@RestController
@RequestMapping("/api/platform/stores")
public class PlatformStoreController {

    /** M8: lista blanca con vuelta silenciosa, como el resto de los listados. */
    private static final Set<String> ALLOWED_SORTS = Set.of("createdAt", "name", "slug", "status");
    private static final int STORES_PAGE_SIZE = 20;
    private static final int AUDIT_PAGE_SIZE = 50;

    private final PlatformStoreQueryService queryService;
    private final StoreLifecycleService lifecycleService;
    private final StoreClosureService closureService;

    public PlatformStoreController(PlatformStoreQueryService queryService, StoreLifecycleService lifecycleService,
                                   StoreClosureService closureService) {
        this.queryService = queryService;
        this.lifecycleService = lifecycleService;
        this.closureService = closureService;
    }

    @GetMapping
    public Page<PlatformStoreDto> index(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) StoreStatus status,
            @RequestParam(required = false) Long companyId,
            @RequestParam(defaultValue = "createdAt") String sortField,
            @RequestParam(defaultValue = "desc") String sortDirection,
            @RequestParam(defaultValue = "1") int page
    ) {
        String safeSort = ALLOWED_SORTS.contains(sortField) ? sortField : "createdAt";
        Sort.Direction direction = "asc".equalsIgnoreCase(sortDirection) ? Sort.Direction.ASC : Sort.Direction.DESC;
        // El id desempata: con muchas tiendas del mismo nombre o estado, el orden de una página a otra sería libre.
        Sort sort = Sort.by(direction, safeSort).and(Sort.by(direction, "id"));
        return queryService.list(search, status, companyId, PageRequest.of(Math.max(page - 1, 0), STORES_PAGE_SIZE, sort));
    }

    @GetMapping("/{id}")
    public PlatformStoreDto show(@PathVariable Long id) {
        return queryService.get(id);
    }

    /**
     * @param from desde, inclusive (ISO-8601, por ejemplo {@code 2026-10-01T00:00:00Z})
     * @param to hasta, exclusive
     */
    @GetMapping("/{id}/audit-events")
    public Page<PlatformAuditEventDto> auditEvents(
            @PathVariable Long id,
            @RequestParam(required = false) AuditAction action,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(defaultValue = "1") int page
    ) {
        Sort newestFirst = Sort.by(Sort.Direction.DESC, "occurredAt").and(Sort.by(Sort.Direction.DESC, "id"));
        return queryService.auditEvents(id, action, from, to,
                PageRequest.of(Math.max(page - 1, 0), AUDIT_PAGE_SIZE, newestFirst));
    }

    /** Por ejemplo cuando la empresa da de baja el módulo en el POS. Repetirlo no cambia nada. */
    @PostMapping("/{id}/suspend")
    public PlatformStoreDto suspend(@PathVariable Long id, @Valid @RequestBody SuspendStoreRequest request) {
        return lifecycleService.suspend(id, request.reason());
    }

    @PostMapping("/{id}/reactivate")
    public PlatformStoreDto reactivate(@PathVariable Long id) {
        return lifecycleService.reactivate(id);
    }

    @PostMapping("/{id}/owner/access-recovery")
    public PlatformStoreDto recoverOwnerAccess(@PathVariable Long id,
                                               @Valid @RequestBody(required = false) OwnerAccessRecoveryRequest request) {
        return lifecycleService.recoverOwnerAccess(id, request == null ? null : request.email());
    }

    @PostMapping("/{id}/close")
    public PlatformStoreDto close(@PathVariable Long id, @Valid @RequestBody CloseStoreRequest request) {
        return closureService.close(id, request.confirmSlug(), request.reason());
    }
}
