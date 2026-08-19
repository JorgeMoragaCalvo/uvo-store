package org.uvo.uvostore.controller.admin.order;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.Instant;

public record CouponRequest(
        @NotBlank String code,
        @NotBlank String name,
        String description,
        @NotBlank String type,
        @NotNull BigDecimal value,
        BigDecimal minimumPurchase,
        BigDecimal maximumDiscount,
        Instant startsAt,
        Instant expiresAt,
        Integer usageLimit,
        Integer usageLimitPerCustomer,
        boolean active
) {
}
