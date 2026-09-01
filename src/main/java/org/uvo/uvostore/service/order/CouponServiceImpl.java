package org.uvo.uvostore.service.order;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.uvo.uvostore.entity.customer.Customer;
import org.uvo.uvostore.entity.order.Coupon;
import org.uvo.uvostore.entity.order.CouponUsage;
import org.uvo.uvostore.entity.order.Order;
import org.uvo.uvostore.entity.order.enums.CouponType;
import org.uvo.uvostore.repository.CouponRepository;
import org.uvo.uvostore.repository.CouponUsageRepository;
import org.uvo.uvostore.security.TenantContext;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;

@Service
public class CouponServiceImpl implements CouponService {

    private final CouponRepository couponRepository;
    private final CouponUsageRepository couponUsageRepository;

    public CouponServiceImpl(CouponRepository couponRepository, CouponUsageRepository couponUsageRepository) {
        this.couponRepository = couponRepository;
        this.couponUsageRepository = couponUsageRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public CouponValidationResult validate(String code, BigDecimal subtotal, Long customerId) {
        Coupon coupon = couponRepository.findByStoreIdAndCode(TenantContext.requireStoreId(), code).orElse(null);
        if (coupon == null) {
            return new CouponValidationResult(false, "Coupon not found", null);
        }
        if (!coupon.isActive()) {
            return new CouponValidationResult(false, "El cupón no está activo.", coupon);
        }

        Instant now = Instant.now();
        if (coupon.getStartsAt() != null && now.isBefore(coupon.getStartsAt())) {
            return new CouponValidationResult(false, "El cupón aún no está disponible.", coupon);
        }
        if (coupon.getExpiresAt() != null && now.isAfter(coupon.getExpiresAt())) {
            return new CouponValidationResult(false, "El cupón ha expirado.", coupon);
        }

        if (coupon.getUsageLimit() != null && coupon.getTimesUsed() >= coupon.getUsageLimit()) {
            return new CouponValidationResult(false, "El cupón ha alcanzado su límite de usos.", coupon);
        }

        if (coupon.getUsageLimitPerCustomer() != null && customerId != null) {
            long customerUsage = couponUsageRepository.countByCouponIdAndCustomerId(coupon.getId(), customerId);
            if (customerUsage >= coupon.getUsageLimitPerCustomer()) {
                return new CouponValidationResult(false, "Ya has usado este cupón el máximo de veces permitido.", coupon);
            }
        }

        if (coupon.getMinimumPurchase() != null && subtotal.compareTo(coupon.getMinimumPurchase()) < 0) {
            return new CouponValidationResult(false, "El monto mínimo de compra para este cupón no se alcanzó.", coupon);
        }

        return new CouponValidationResult(true, "Cupón válido", coupon);
    }

    @Override
    public BigDecimal calculateDiscount(Coupon coupon, BigDecimal subtotal) {
        if (coupon.getType() == CouponType.PERCENTAGE) {
            BigDecimal discount = subtotal.multiply(coupon.getValue()).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
            if (coupon.getMaximumDiscount() != null && discount.compareTo(coupon.getMaximumDiscount()) > 0) {
                discount = coupon.getMaximumDiscount();
            }
            return discount;
        }
        return coupon.getValue().min(subtotal);
    }

    // C5: validate() above is a read, so two concurrent checkouts can both pass its usage_limit
    // check. This is the statement that actually enforces the limit — the condition lives in the
    // UPDATE, so the database serialises the claims and only one of them affects a row.
    @Override
    @Transactional
    public boolean claimUsage(Coupon coupon) {
        return couponRepository.claimUsage(coupon.getId()) == 1;
    }

    @Override
    @Transactional
    public void releaseUsage(Coupon coupon) {
        couponRepository.releaseUsage(coupon.getId());
    }

    // Only the usage row: incrementing times_used is claimUsage's job, and it already ran before
    // the order was saved. Both happen inside the checkout's transaction, so a later failure rolls
    // back the claim too.
    @Override
    @Transactional
    public void recordUsage(Coupon coupon, Order order, Customer customer) {
        CouponUsage usage = new CouponUsage();
        usage.setCoupon(coupon);
        usage.setOrder(order);
        usage.setCustomer(customer);
        usage.setDiscountAmount(calculateDiscount(coupon, order.getSubtotal()));
        couponUsageRepository.save(usage);
    }
}
