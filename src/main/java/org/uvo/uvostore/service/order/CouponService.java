package org.uvo.uvostore.service.order;

import org.uvo.uvostore.entity.customer.Customer;
import org.uvo.uvostore.entity.order.Coupon;
import org.uvo.uvostore.entity.order.Order;

import java.math.BigDecimal;

public interface CouponService {
    CouponValidationResult validate(String code, BigDecimal subtotal, Long customerId);
    BigDecimal calculateDiscount(Coupon coupon, BigDecimal subtotal);

    /**
     * Atomically takes one use of the coupon, enforcing usage_limit in the same statement.
     * Returns false when the coupon ran out — validate() alone can't guarantee this, since another
     * checkout can consume the last use between the two calls.
     */
    boolean claimUsage(Coupon coupon);

    /** Gives a claimed use back (cancelled order or failed payment). */
    void releaseUsage(Coupon coupon);

    /** Records who used it, once the order has an id. The claim happens earlier, in claimUsage. */
    void recordUsage(Coupon coupon, Order order, Customer customer);
}
