package org.uvo.uvostore.controller.pos;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.Map;

/** El aviso {@code product.created} del POS (contrato §5). IMP-08: con su {@code version}, como todos salvo la alerta. */
public record ProductCreatedRequest(
        @NotBlank String event,
        @NotNull Long productId,
        @NotBlank String sku,
        @NotNull Long companyId,
        @NotNull Long version,
        @NotEmpty Map<String, Object> data
) {
}
