package org.uvo.uvostore.controller.pos;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.Map;

public record ProductCreatedRequest(
        @NotBlank String event,
        @NotNull Long productId,
        @NotBlank String sku,
        @NotNull Long companyId,
        @NotEmpty Map<String, Object> data
) {
}
