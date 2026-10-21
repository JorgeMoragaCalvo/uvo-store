package org.uvo.uvostore.controller.pos;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.Map;

public record ProductUpdatedRequest(
        @NotBlank String event,
        @NotNull Long productId,
        @NotBlank String sku,
        @NotNull Long companyId,
        // POS-3. Obligatoria, como en la sincronización: la versión del producto en el POS. data tiene que
        // traer todos los campos del POS, no solo los de changedFields (ver PosCatalogWriter).
        @NotNull Long version,
        @NotEmpty List<String> changedFields,
        @NotEmpty Map<String, Object> data
) {
}
