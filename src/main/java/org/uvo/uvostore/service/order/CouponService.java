package org.uvo.uvostore.service.order;

import org.uvo.uvostore.entity.customer.Customer;
import org.uvo.uvostore.entity.order.Coupon;
import org.uvo.uvostore.entity.order.Order;

import java.math.BigDecimal;

public interface CouponService {
    CouponValidationResult validate(String code, BigDecimal subtotal, Long customerId);
    BigDecimal calculateDiscount(Coupon coupon, BigDecimal subtotal);
    void recordUsage(Coupon coupon, Order order, Customer customer);
}
