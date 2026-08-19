package org.uvo.uvostore.service.order;

import java.math.BigDecimal;

public record CartCalculationResult(
        BigDecimal subtotalWithoutTax,
        BigDecimal subtotalWithTax,
        BigDecimal shippingCost,
        BigDecimal taxAmount,
        BigDecimal discountAmount,
        BigDecimal total,
        boolean pricesIncludeTax,
        BigDecimal taxRate,
        BigDecimal freeShippingThreshold,
        boolean shippingEnabled
) {
}
