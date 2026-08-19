package org.uvo.uvostore.controller.pos;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record StockUpdatedRequest(
        @NotBlank String event,
        @NotNull Long productId,
        @NotBlank String sku,
        @NotNull Long companyId,
        @NotNull Long warehouseId,
        @NotNull Integer oldStock,
        @NotNull Integer newStock,
        @NotNull Integer stockWeb
) {
}
