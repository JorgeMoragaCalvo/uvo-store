package org.uvo.uvostore.service.order;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface AdminCouponService {
    // statusFilter: "all" | "active" | "inactive" | "expired" — ports Admin\Coupons\Index::render().
    Page<CouponDto> search(String search, String statusFilter, Pageable pageable);
    CouponDto getById(Long id);
    CouponDto create(CouponCommand command);
    CouponDto update(Long id, CouponCommand command);
    void delete(Long id);
    CouponDto toggleStatus(Long id);
}
