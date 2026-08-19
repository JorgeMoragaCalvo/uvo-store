package org.uvo.uvostore.controller.pos;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.uvo.uvostore.service.pos.PosSyncService;
import org.uvo.uvostore.service.pos.SyncProductCommand;
import org.uvo.uvostore.service.pos.SyncProductResult;

import java.util.Map;

@RestController
@RequestMapping("/api/sync")
public class PosSyncController {

    private final PosSyncService posSyncService;

    public PosSyncController(PosSyncService posSyncService) {
        this.posSyncService = posSyncService;
    }

    @PostMapping("/product")
    public Map<String, Object> syncProduct(@Valid @RequestBody SyncProductRequest request) {
        SyncProductCommand command = new SyncProductCommand(
                request.companyId(), request.externalId(), request.sku(), request.name(), request.description(),
                request.price(), request.stock(), request.warehouseId(), request.categoryName(), request.active()
        );
        SyncProductResult result = posSyncService.syncProduct(command);
        return Map.of(
                "success", true,
                "message", result.created() ? "Producto creado" : "Producto actualizado",
                "product_id", result.productId()
        );
    }
}
