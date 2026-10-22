package org.uvo.uvostore.controller.pos;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

public record SyncProductRequest(
        @NotNull Long companyId,
        @NotNull Long externalId,
        @NotBlank String sku,
        @NotBlank String name,
        String description,
        // POS-5. Obligatorios en un producto sin variantes (lo comprueba PosSyncServiceImpl); con variantes no se
        // usan, porque el padre resume las suyas.
        BigDecimal price,
        Integer stock,
        Long warehouseId,
        String categoryName,
        Boolean active,
        // POS-3. Obligatoria: la versión del producto en el POS, que crece con cada cambio. Sin ella no hay
        // forma de saber si lo que llega es más viejo que lo ya aplicado.
        @NotNull Long version,
        // POS-5. El estado completo de sus variantes, o nada si es un producto simple. Las reglas de la lista las
        // aplica PosVariants.validate, la misma para las tres entradas del catálogo.
        List<Variant> variants
) {

    public record Variant(Long variantId, String sku, BigDecimal price, Integer stock, Map<String, String> attributes) {
    }
}
