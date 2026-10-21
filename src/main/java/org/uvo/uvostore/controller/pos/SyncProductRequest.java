package org.uvo.uvostore.controller.pos;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

public record SyncProductRequest(
        @NotNull Long companyId,
        @NotNull Long externalId,
        @NotBlank String sku,
        @NotBlank String name,
        String description,
        @NotNull BigDecimal price,
        @NotNull Integer stock,
        Long warehouseId,
        String categoryName,
        Boolean active,
        // POS-3. Obligatoria: la versión del producto en el POS, que crece con cada cambio. Sin ella no hay
        // forma de saber si lo que llega es más viejo que lo ya aplicado.
        @NotNull Long version
) {
}
