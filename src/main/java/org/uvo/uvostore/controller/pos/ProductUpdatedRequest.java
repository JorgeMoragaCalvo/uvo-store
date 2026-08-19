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
        @NotEmpty List<String> changedFields,
        @NotEmpty Map<String, Object> data
) {
}
