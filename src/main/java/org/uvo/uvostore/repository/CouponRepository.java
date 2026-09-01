package org.uvo.uvostore.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.uvo.uvostore.entity.order.Coupon;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface CouponRepository extends JpaRepository<Coupon, Long>, JpaSpecificationExecutor<Coupon> {

    Optional<Coupon> findByCode(String code);
    boolean existsByCode(String code);
    List<Coupon> findByIsActiveTrue();
    List<Coupon> findByIsActiveTrueAndExpiresAtAfter(Instant now);

    // Tenant-scoped: code is now UNIQUE(store_id, code), not globally unique.
    Optional<Coupon> findByStoreIdAndCode(Long storeId, String code);
    boolean existsByStoreIdAndCode(Long storeId, String code);

    // C5: claiming a use and enforcing the limit have to be one statement. Validating `timesUsed <
    // usageLimit` in Java and then saving the incremented entity lets two concurrent checkouts both
    // pass the check and redeem a single-use coupon twice. 0 rows affected means the coupon ran out
    // between the price quote and the claim. A NULL usageLimit means unlimited, so it always claims.
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        UPDATE Coupon c SET c.timesUsed = c.timesUsed + 1
        WHERE c.id = :id AND (c.usageLimit IS NULL OR c.timesUsed < c.usageLimit)
        """)
    int claimUsage(@Param("id") Long id);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Coupon c SET c.timesUsed = c.timesUsed - 1 WHERE c.id = :id AND c.timesUsed > 0")
    int releaseUsage(@Param("id") Long id);
}
