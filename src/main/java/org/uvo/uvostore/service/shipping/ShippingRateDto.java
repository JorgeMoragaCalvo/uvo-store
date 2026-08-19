package org.uvo.uvostore.service.shipping;

import java.math.BigDecimal;

public record ShippingRateDto(
        Long id,
        Long methodId,
        String methodName,
        Long zoneId,
        String zoneName,
        String name,
        String rateType,
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
