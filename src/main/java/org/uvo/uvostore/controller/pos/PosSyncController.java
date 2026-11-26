package org.uvo.uvostore.controller.pos;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.uvo.uvostore.entity.pos.PosConnection;
import org.uvo.uvostore.security.PosRequestGuard;
import org.uvo.uvostore.service.pos.PosSyncService;
import org.uvo.uvostore.service.pos.SyncProductCommand;
import org.uvo.uvostore.service.pos.SyncProductResult;

import java.util.Map;

// F01: la tienda donde se crea o actualiza el producto sale de la conexión que autenticó
// PosSignatureAuthFilter (atributo "posConnection"), no del companyId del cuerpo. Ver PosRequestGuard.
//
// IMP-08: contrato UvoPOS ↔ uvo-store v1, §5. Un Producto del POS por petición; una variante es un Producto del POS
// más, que se vincula a su variación desde el panel.
@io.swagger.v3.oas.annotations.tags.Tag(name = "Sincronización POS", description = "Carga de productos desde UvoPOS — firma UVO-HMAC-SHA256 (X-Uvo-Key-Id, X-Uvo-Timestamp, X-Uvo-Nonce, X-Uvo-Signature, X-Company-ID, X-Uvo-Contract: 1)")
@RestController
@RequestMapping("/api/sync")
public class PosSyncController {

    private final PosSyncService posSyncService;

    public PosSyncController(PosSyncService posSyncService) {
        this.posSyncService = posSyncService;
    }

    @PostMapping("/product")
    public Map<String, Object> syncProduct(@RequestAttribute("posConnection") PosConnection connection,
                                           @Valid @RequestBody SyncProductRequest request) {
        PosRequestGuard.companyIdOf(connection, request.companyId());
        SyncProductCommand command = new SyncProductCommand(
                request.externalId(), request.sku(), request.name(), request.description(),
                request.price(), request.stock(), request.warehouseId(), request.categoryName(), request.active(),
                request.version(), request.taxExempt()
        );
        SyncProductResult result = posSyncService.syncProduct(connection, command);
        // POS-3. Un descarte también es 200: la petición se entendió y no tiene sentido reintentarla. applied y
        // message dicen qué pasó; ignored, como en los avisos (§5).
        return Map.of(
                "success", true,
                "ignored", !result.applied() && result.ignored(),
                "message", result.message(),
                "product_id", result.productId(),
                "applied", result.applied(),
                "log_id", result.logId()
        );
    }
}
