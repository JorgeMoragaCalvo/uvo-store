package org.uvo.uvostore.controller.pos;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import java.math.BigDecimal;

/**
 * La carga de un Producto del POS ({@code POST /api/sync/product}, contrato §5). IMP-08: un producto por petición y
 * sin variantes: cada variante es un Producto del POS más (§4). {@code stock} es el stock web.
 */
public record SyncProductRequest(
        @NotNull Long companyId,
        @NotNull Long externalId,
        @NotBlank String sku,
        @NotBlank String name,
        String description,
        @NotNull BigDecimal price,
        @NotNull @PositiveOrZero Integer stock,
        Long warehouseId,
        String categoryName,
        Boolean active,
        // POS-3. Obligatoria: la versión del producto en el POS, que crece con cada cambio.
        @NotNull Long version,
        // IMP-08. Exento de IVA; si no viene, se deja como está (al crear, no exento).
        Boolean taxExempt
) {
}
