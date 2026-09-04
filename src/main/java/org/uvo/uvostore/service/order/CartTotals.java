package org.uvo.uvostore.service.order;

import java.math.BigDecimal;

/**
 * @param shippingAvailable false when the store does ship but no zone covers the given
 *        region/commune. Without it a caller can't tell "shipping is free" from "we don't deliver
 *        there" — both used to surface as a cost of zero, which is how every order ended up with
 *        free shipping (A7).
 * @param couponApplied true only when a coupon code was supplied AND it was valid, so the
 *        storefront can say "that code is wrong" instead of silently showing no discount.
 */
public record CartTotals(
        BigDecimal subtotalWithoutTax, BigDecimal taxAmount, BigDecimal subtotalWithTax,
        BigDecimal shippingCost, BigDecimal discountAmount, BigDecimal total,
        boolean shippingAvailable, boolean couponApplied
) {
}
