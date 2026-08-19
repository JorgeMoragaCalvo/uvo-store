package org.uvo.uvostore.controller.pos;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

public record StockAlertRequest(
        @NotBlank String event,
        @NotNull Long productId,
        @NotBlank String sku,
        @NotNull Long companyId,
        @NotNull Integer currentStock,
        @NotNull Integer currentStockWeb,
        @NotBlank @Pattern(regexp = "out_of_stock|low_stock") String alertType
) {
}
