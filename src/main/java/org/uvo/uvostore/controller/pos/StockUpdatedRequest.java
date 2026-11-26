package org.uvo.uvostore.controller.pos;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * El aviso {@code stock.updated} del POS (contrato §5). IMP-08: {@code productId} es un Producto del POS, sea un
 * producto simple o una variante de la tienda; ya no hay {@code variantId}. {@code version} ordena los avisos.
 * {@code oldStock} y {@code newStock} son el stock total de la bodega y solo se registran: lo que se aplica es
 * {@code stockWeb}.
 */
public record StockUpdatedRequest(
        @NotBlank String event,
        @NotNull Long productId,
        @NotBlank String sku,
        @NotNull Long companyId,
        Long warehouseId,
        @NotNull Long version,
        Integer oldStock,
        Integer newStock,
        @NotNull @PositiveOrZero Integer stockWeb
) {
}
