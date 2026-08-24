package org.uvo.uvostore.controller.admin.shipping;

import jakarta.validation.constraints.NotBlank;

public record ShippingMethodRequest(
        @NotBlank String name,
        @NotBlank String code,
        String description,
        @NotBlank String type,
        boolean hasApiIntegration,
        String carrier,
        Integer minDeliveryDays,
        Integer maxDeliveryDays,
        boolean active,
        int sortOrder
) {
}
