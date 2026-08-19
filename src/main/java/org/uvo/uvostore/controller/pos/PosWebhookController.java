package org.uvo.uvostore.controller.pos;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.uvo.uvostore.entity.pos.SyncWebhookLog;
import org.uvo.uvostore.service.pos.PosWebhookService;
import org.uvo.uvostore.service.pos.ProductCreatedPayload;
import org.uvo.uvostore.service.pos.ProductUpdatePayload;
import org.uvo.uvostore.service.pos.StockAlertPayload;
import org.uvo.uvostore.service.pos.StockUpdatePayload;

import java.util.Map;

@RestController
@RequestMapping("/api/webhooks/pos")
public class PosWebhookController {

    private final PosWebhookService posWebhookService;

    public PosWebhookController(PosWebhookService posWebhookService) {
        this.posWebhookService = posWebhookService;
    }

    @PostMapping("/stock-updated")
    public Map<String, Object> stockUpdated(@Valid @RequestBody StockUpdatedRequest request) {
        SyncWebhookLog log = posWebhookService.handleStockUpdated(new StockUpdatePayload(
                request.companyId(), request.productId(), request.sku(),
                request.oldStock(), request.newStock(), request.stockWeb()));
        return receivedResponse(log);
    }

    @PostMapping("/product-updated")
    public Map<String, Object> productUpdated(@Valid @RequestBody ProductUpdatedRequest request) {
        SyncWebhookLog log = posWebhookService.handleProductUpdated(new ProductUpdatePayload(
                request.companyId(), request.productId(), request.sku(), request.data()));
        return receivedResponse(log);
    }

    @PostMapping("/stock-alert")
    public Map<String, Object> stockAlert(@Valid @RequestBody StockAlertRequest request) {
        posWebhookService.handleStockAlert(new StockAlertPayload(
                request.companyId(), request.productId(), request.sku(),
                request.currentStock(), request.currentStockWeb(), request.alertType()));
        return Map.of("success", true, "message", "Alerta de stock recibida");
    }

    @PostMapping("/product-created")
    public Map<String, Object> productCreated(@Valid @RequestBody ProductCreatedRequest request) {
        posWebhookService.handleProductCreated(new ProductCreatedPayload(
                request.companyId(), request.productId(), request.sku(), request.data()));
        return Map.of(
                "success", true,
                "message", "Producto creado notificado",
                "note", "Auto-creación pendiente de implementación"
        );
    }

    private Map<String, Object> receivedResponse(SyncWebhookLog log) {
        return Map.of(
                "success", true,
                "message", "Webhook recibido, procesando en segundo plano",
                "log_id", log.getId()
        );
    }
}
