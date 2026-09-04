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
        boolean shippingEnabled,
        // A7. shippingAvailable=false means the store ships but doesn't reach this region/commune —
        // the storefront must render that as "no delivery here", never as free shipping.
        // couponApplied=false alongside a non-empty code entered means the code was rejected.
        boolean shippingAvailable,
        boolean couponApplied
) {
}
