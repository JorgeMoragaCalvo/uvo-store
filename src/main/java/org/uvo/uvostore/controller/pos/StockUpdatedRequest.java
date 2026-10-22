package org.uvo.uvostore.controller.pos;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record StockUpdatedRequest(
        @NotBlank String event,
        @NotNull Long productId,
        // POS-5. La variante cuyo stock cambió. Obligatoria si el producto tiene variantes (lo comprueba
        // PosWebhookServiceImpl): sin ella se escribiría el stock del padre, que es la suma de sus variantes.
        Long variantId,
        @NotBlank String sku,
        @NotNull Long companyId,
        @NotNull Long warehouseId,
        @NotNull Integer oldStock,
        @NotNull Integer newStock,
        @NotNull Integer stockWeb
) {
}
