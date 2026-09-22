package org.uvo.uvostore.controller.pos;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.uvo.uvostore.entity.pos.PosConnection;
import org.uvo.uvostore.entity.pos.SyncWebhookLog;
import org.uvo.uvostore.security.PosRequestGuard;
import org.uvo.uvostore.service.pos.PosWebhookService;
import org.uvo.uvostore.service.pos.ProductCreatedPayload;
import org.uvo.uvostore.service.pos.ProductUpdatePayload;
import org.uvo.uvostore.service.pos.StockAlertPayload;
import org.uvo.uvostore.service.pos.StockUpdatePayload;

import java.util.Map;

// F01: el companyId que llega a los servicios es el de la conexión que autenticó PosWebhookAuthFilter
// (atributo "posConnection"), no el del cuerpo. Antes se pasaba request.companyId() tal cual, y como
// la firma se valida con el secreto de la conexión de la CABECERA, cualquier comercio podía firmar
// con lo suyo y declarar la empresa de otro en el JSON. Ver PosRequestGuard.
@io.swagger.v3.oas.annotations.tags.Tag(name = "Webhooks POS", description = "UvoPOS notifica cambios de stock/producto — firma HMAC (X-Signature/X-Company-ID/X-Timestamp), no probable desde el botón Authorize")
@RestController
@RequestMapping("/api/webhooks/pos")
public class PosWebhookController {

    private final PosWebhookService posWebhookService;

    public PosWebhookController(PosWebhookService posWebhookService) {
        this.posWebhookService = posWebhookService;
    }

    @PostMapping("/stock-updated")
    public Map<String, Object> stockUpdated(@RequestAttribute("posConnection") PosConnection connection,
                                            @Valid @RequestBody StockUpdatedRequest request) {
        SyncWebhookLog log = posWebhookService.handleStockUpdated(new StockUpdatePayload(
                PosRequestGuard.companyIdOf(connection, request.companyId()), request.productId(), request.sku(),
                request.oldStock(), request.newStock(), request.stockWeb()));
        return receivedResponse(log);
    }

    @PostMapping("/product-updated")
    public Map<String, Object> productUpdated(@RequestAttribute("posConnection") PosConnection connection,
                                              @Valid @RequestBody ProductUpdatedRequest request) {
        SyncWebhookLog log = posWebhookService.handleProductUpdated(new ProductUpdatePayload(
                PosRequestGuard.companyIdOf(connection, request.companyId()), request.productId(),
                request.sku(), request.data()));
        return receivedResponse(log);
    }

    @PostMapping("/stock-alert")
    public Map<String, Object> stockAlert(@RequestAttribute("posConnection") PosConnection connection,
                                          @Valid @RequestBody StockAlertRequest request) {
        posWebhookService.handleStockAlert(new StockAlertPayload(
                PosRequestGuard.companyIdOf(connection, request.companyId()), request.productId(), request.sku(),
                request.currentStock(), request.currentStockWeb(), request.alertType()));
        return Map.of("success", true, "message", "Alerta de stock recibida");
    }

    @PostMapping("/product-created")
    public Map<String, Object> productCreated(@RequestAttribute("posConnection") PosConnection connection,
                                              @Valid @RequestBody ProductCreatedRequest request) {
        posWebhookService.handleProductCreated(new ProductCreatedPayload(
                PosRequestGuard.companyIdOf(connection, request.companyId()), request.productId(),
                request.sku(), request.data()));
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
