package org.uvo.uvostore.service.order;

import java.math.BigDecimal;

public record CartTotals(
        BigDecimal subtotalWithoutTax, BigDecimal taxAmount, BigDecimal subtotalWithTax,
        BigDecimal shippingCost, BigDecimal discountAmount, BigDecimal total
) {
}
