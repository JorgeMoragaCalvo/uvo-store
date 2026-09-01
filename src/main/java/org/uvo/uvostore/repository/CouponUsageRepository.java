package org.uvo.uvostore.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.uvo.uvostore.entity.order.CouponUsage;

import java.util.List;

@Repository
public interface CouponUsageRepository extends JpaRepository<CouponUsage, Long> {

    List<CouponUsage> findByCouponId(Long couponId);
    long countByCouponIdAndCustomerId(Long couponId, Long customerId); // usage_limit_per_customer check

    // C5: releasing a coupon use deletes its row, and UNIQUE(coupon_id, order_id) guarantees there
    // is at most one per order. The delete's row count is therefore the idempotency guard — 0 means
    // the use was already returned, so times_used must not be decremented a second time.
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM CouponUsage u WHERE u.order.id = :orderId")
    int deleteByOrderId(@Param("orderId") Long orderId);
}
