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
import org.uvo.uvostore.service.pos.PosVariantFields;
import org.uvo.uvostore.service.pos.SyncProductCommand;
import org.uvo.uvostore.service.pos.SyncProductResult;

import java.util.List;
import java.util.Map;

// F01: la tienda donde se crea o actualiza el producto sale de la conexión que autenticó
// PosApiKeyAuthFilter (atributo "posConnection"), no del companyId del cuerpo. Esta era la vía fuerte
// del fallo: a diferencia de los webhooks, aquí no hace falta ningún mapping previo, así que un
// comercio podía crear productos activos en el catálogo de otro sin más que cambiar un número del
// JSON. Ver PosRequestGuard.
@io.swagger.v3.oas.annotations.tags.Tag(name = "Sincronización POS", description = "Sincronización de productos con UvoPOS — API key (Authorization: Bearer + X-Company-ID)")
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
                request.version(), variants(request.variants())
        );
        SyncProductResult result = posSyncService.syncProduct(connection, command);
        // POS-3. Un descarte también es 200: la petición se entendió y no tiene sentido reintentarla. applied y
        // message dicen qué pasó.
        return Map.of(
                "success", true,
                "message", result.message(),
                "product_id", result.productId(),
                "applied", result.applied(),
                "log_id", result.logId()
        );
    }

    // Una variante null dentro de la lista llega como una sin campos, para que la rechace PosVariants.validate con
    // el mismo mensaje que cualquier otra variante incompleta.
    private static List<PosVariantFields> variants(List<SyncProductRequest.Variant> variants) {
        if (variants == null) {
            return null;
        }
        return variants.stream()
                .map(variant -> variant == null
                        ? new PosVariantFields(null, null, null, null, null)
                        : new PosVariantFields(variant.variantId(), variant.sku(), variant.price(), variant.stock(),
                                variant.attributes()))
                .toList();
    }
}
