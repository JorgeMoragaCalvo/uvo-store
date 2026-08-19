package org.uvo.uvostore.controller.order;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

public record CartItemRequest(
        @NotNull Long id,
        @NotNull @Pattern(regexp = "product|variation") String type,
        @Min(1) int quantity
) {
}
