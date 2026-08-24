package org.uvo.uvostore.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
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
}
