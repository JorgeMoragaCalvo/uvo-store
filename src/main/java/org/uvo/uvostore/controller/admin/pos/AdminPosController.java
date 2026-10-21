package org.uvo.uvostore.controller.admin.pos;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.uvo.uvostore.security.TenantContext;
import org.uvo.uvostore.service.pos.PosReconciliationService;
import org.uvo.uvostore.service.pos.PosReconciliationSummary;

/**
 * POS-4. La resincronización con UvoPOS a demanda, desde Ajustes &gt; POS. Concilia ahora todos los productos
 * mapeados de la tienda, con las mismas reglas que la pasada programada.
 *
 * <p>Síncrona: devuelve el resumen al terminar. Con las tiendas del piloto (cientos de productos, lotes de 200) son
 * pocas lecturas al POS; si algún día una tienda tiene un catálogo grande, pasa a encolarse.
 */
@io.swagger.v3.oas.annotations.tags.Tag(name = "UvoPOS (admin)", description = "Resincronización con el POS, JWT bearer con rol ADMIN")
@io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/api/admin/pos")
public class AdminPosController {

    private final PosReconciliationService reconciliationService;

    public AdminPosController(PosReconciliationService reconciliationService) {
        this.reconciliationService = reconciliationService;
    }

    // settings.manage y no settings.view: corrige stock y precios.
    @PostMapping("/resync")
    @PreAuthorize("hasAuthority('settings.manage')")
    public PosReconciliationSummary resync() {
        return reconciliationService.reconcileStore(TenantContext.requireCurrent());
    }
}
