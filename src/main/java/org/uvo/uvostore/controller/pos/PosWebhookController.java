package org.uvo.uvostore.controller.pos;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.uvo.uvostore.entity.pos.PosConnection;
import org.uvo.uvostore.entity.pos.SyncWebhookLog;
import org.uvo.uvostore.entity.pos.enums.WebhookStatus;
import org.uvo.uvostore.security.PosRequestGuard;
import org.uvo.uvostore.service.pos.PosWebhookService;
import org.uvo.uvostore.service.pos.ProductCreatedPayload;
import org.uvo.uvostore.service.pos.ProductUpdatePayload;
import org.uvo.uvostore.service.pos.StockAlertPayload;
import org.uvo.uvostore.service.pos.StockUpdatePayload;

import java.util.Map;

// F01: el companyId que llega a los servicios es el de la conexión que autenticó PosSignatureAuthFilter
// (atributo "posConnection"), no el del cuerpo. Ver PosRequestGuard.
//
// IMP-08: contrato UvoPOS ↔ uvo-store v1, §5. 2xx = recibido; un aviso con una versión ya aplicada o más vieja
// responde 200 con "ignored": true y no hace nada.
@io.swagger.v3.oas.annotations.tags.Tag(name = "Webhooks POS", description = "UvoPOS notifica cambios de stock/producto — firma UVO-HMAC-SHA256 (X-Uvo-Key-Id, X-Uvo-Timestamp, X-Uvo-Nonce, X-Uvo-Signature, X-Company-ID, X-Uvo-Contract: 1), no probable desde el botón Authorize")
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
                PosRequestGuard.companyIdOf(connection, request.companyId()), request.productId(),
                request.sku(), request.version(), request.oldStock(), request.newStock(), request.stockWeb()));
        return receivedResponse(log);
    }

    @PostMapping("/product-updated")
    public Map<String, Object> productUpdated(@RequestAttribute("posConnection") PosConnection connection,
                                              @Valid @RequestBody ProductUpdatedRequest request) {
        SyncWebhookLog log = posWebhookService.handleProductUpdated(new ProductUpdatePayload(
                PosRequestGuard.companyIdOf(connection, request.companyId()), request.productId(),
                request.sku(), request.version(), request.data()));
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
        SyncWebhookLog log = posWebhookService.handleProductCreated(new ProductCreatedPayload(
                PosRequestGuard.companyIdOf(connection, request.companyId()), request.productId(),
                request.sku(), request.version(), request.data()));
        return receivedResponse(log);
    }

    private Map<String, Object> receivedResponse(SyncWebhookLog log) {
        boolean ignored = log.getStatus() == WebhookStatus.DISCARDED;
        return Map.of(
                "success", true,
                "ignored", ignored,
                "message", ignored ? "Aviso ignorado: la versión ya estaba aplicada o es más vieja" : "Aviso recibido",
                "log_id", log.getId()
        );
    }
}
