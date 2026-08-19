package org.uvo.uvostore.service.order;

import org.uvo.uvostore.entity.order.Coupon;

public record CouponValidationResult(boolean valid, String reason, Coupon coupon) {
}
