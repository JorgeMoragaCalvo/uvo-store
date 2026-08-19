package org.uvo.uvostore.service.order;

import java.math.BigDecimal;
import java.time.Instant;

public record CouponDto(
        Long id,
        String code,
        String name,
        String description,
        String type,
        BigDecimal value,
        BigDecimal minimumPurchase,
        BigDecimal maximumDiscount,
        Instant startsAt,
        Instant expiresAt,
        Integer usageLimit,
        Integer usageLimitPerCustomer,
        int timesUsed,
        boolean active,
        Instant createdAt
) {
}
