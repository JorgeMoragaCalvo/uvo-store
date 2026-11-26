package org.uvo.uvostore.controller.admin.pos;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.uvo.uvostore.entity.audit.enums.AuditAction;
import org.uvo.uvostore.entity.audit.enums.AuditTargetType;
import org.uvo.uvostore.entity.pos.PosConnection;
import org.uvo.uvostore.entity.pos.PosConnectionKey;
import org.uvo.uvostore.repository.PosConnectionRepository;
import org.uvo.uvostore.security.TenantContext;
import org.uvo.uvostore.service.BusinessException;
import org.uvo.uvostore.service.audit.AuditDiff;
import org.uvo.uvostore.service.audit.AuditEntry;
import org.uvo.uvostore.service.audit.AuditTrail;
import org.uvo.uvostore.service.pos.PosConnectionKeyService;
import org.uvo.uvostore.service.pos.PosReconciliationService;
import org.uvo.uvostore.service.pos.PosReconciliationSummary;

import java.util.List;

/**
 * POS-4. La resincronización con UvoPOS a demanda, desde Ajustes &gt; POS. Concilia ahora todos los productos
 * mapeados de la tienda, con las mismas reglas que la pasada programada.
 *
 * <p>Síncrona: devuelve el resumen al terminar. Con las tiendas del piloto (cientos de productos, lotes de 200) son
 * pocas lecturas al POS; si algún día una tienda tiene un catálogo grande, pasa a encolarse.
 *
 * <p>IMP-08. También las llaves de firma de la conexión: verlas (sin secretos) y revocar una en el acto. La alta y la
 * rotación van por el formulario de Ajustes, con el resto de la conexión.
 */
@io.swagger.v3.oas.annotations.tags.Tag(name = "UvoPOS (admin)", description = "Resincronización y llaves de la conexión con el POS, JWT bearer con rol ADMIN")
@io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/api/admin/pos")
public class AdminPosController {

    private final PosReconciliationService reconciliationService;
    private final PosConnectionRepository connectionRepository;
    private final PosConnectionKeyService keyService;
    private final AuditTrail auditTrail;

    public AdminPosController(PosReconciliationService reconciliationService,
                              PosConnectionRepository connectionRepository, PosConnectionKeyService keyService,
                              AuditTrail auditTrail) {
        this.reconciliationService = reconciliationService;
        this.connectionRepository = connectionRepository;
        this.keyService = keyService;
        this.auditTrail = auditTrail;
    }

    // settings.manage y no settings.view: corrige stock y precios.
    @PostMapping("/resync")
    @PreAuthorize("hasAuthority('settings.manage')")
    public PosReconciliationSummary resync() {
        return reconciliationService.reconcileStore(TenantContext.requireCurrent());
    }

    /** Las llaves de la conexión de la tienda, la más nueva primero en cada dirección. Nunca un secreto. */
    @GetMapping("/keys")
    @PreAuthorize("hasAuthority('settings.view')")
    @Transactional(readOnly = true)
    public List<PosConnectionKeyService.KeyView> keys() {
        return connectionRepository.findByStoreId(TenantContext.requireStoreId())
                .map(keyService::describe)
                .orElse(List.of());
    }

    /**
     * Revoca una llave en el acto: desde ese momento ese {@code key_id} da 401 (contrato §2). Si era la única
     * vigente de su dirección, la conexión queda inactiva hasta que se cargue otra.
     */
    @PostMapping("/keys/revoke")
    @PreAuthorize("hasAuthority('settings.manage')")
    @Transactional
    public List<PosConnectionKeyService.KeyView> revoke(@Valid @RequestBody RevokeKeyRequest request) {
        PosConnection connection = connectionRepository.findByStoreId(TenantContext.requireStoreId())
                .orElseThrow(() -> new BusinessException("La tienda no tiene conexión con UvoPOS."));
        boolean revoked = keyService.revoke(connection, request.direction(), request.keyId().trim());
        if (revoked) {
            boolean wasActive = connection.isActive();
            if (!keyService.hasUsableKeys(connection)) {
                connection.setActive(false);
            }
            auditTrail.record(AuditEntry.of(AuditAction.POS_KEY_REVOKE, AuditTargetType.POS_CONNECTION, connection.getId())
                    .details(AuditDiff.create()
                            .with("direction", request.direction().name())
                            .with("keyId", request.keyId().trim())
                            .value("active", wasActive, connection.isActive())));
        }
        return keyService.describe(connection);
    }

    public record RevokeKeyRequest(
            @NotNull(message = "Falta la dirección de la llave") PosConnectionKey.Direction direction,
            @NotBlank(message = "Falta el key_id") String keyId) {
    }
}
