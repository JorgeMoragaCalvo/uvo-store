package org.uvo.uvostore.controller.order;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

public record CartCalculateRequest(
        @NotEmpty @Valid List<CartItemRequest> items,
        String region,
        String commune,
        String couponCode
) {
}
