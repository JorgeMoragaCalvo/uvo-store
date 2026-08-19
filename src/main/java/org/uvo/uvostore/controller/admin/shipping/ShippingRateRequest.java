package org.uvo.uvostore.controller.admin.shipping;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

public record ShippingRateRequest(
        @NotNull Long methodId,
        @NotNull Long zoneId,
        @NotBlank String name,
        @NotBlank String rateType,
        BigDecimal flatRate,
        BigDecimal weightRatePerKg,
        BigDecimal baseWeightRate,
        BigDecimal minOrderAmount,
        BigDecimal maxOrderAmount,
        BigDecimal minWeight,
        BigDecimal maxWeight,
        BigDecimal freeShippingThreshold,
        boolean active,
        int sortOrder
) {
}
