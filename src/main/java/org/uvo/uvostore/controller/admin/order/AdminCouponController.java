package org.uvo.uvostore.controller.admin.order;

import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.uvo.uvostore.service.order.AdminCouponService;
import org.uvo.uvostore.service.order.CouponCommand;
import org.uvo.uvostore.service.order.CouponDto;

@RestController
@RequestMapping("/api/admin/coupons")
public class AdminCouponController {

    private final AdminCouponService adminCouponService;

    public AdminCouponController(AdminCouponService adminCouponService) {
        this.adminCouponService = adminCouponService;
    }

    @GetMapping
    public Page<CouponDto> index(
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "all") String status,
            @RequestParam(defaultValue = "1") int page
    ) {
        return adminCouponService.search(search, status, PageRequest.of(Math.max(page - 1, 0), 15, Sort.by(Sort.Direction.DESC, "createdAt")));
    }

    @GetMapping("/{id}")
    public CouponDto show(@PathVariable Long id) {
        return adminCouponService.getById(id);
    }

    @PostMapping
    public CouponDto create(@Valid @RequestBody CouponRequest request) {
        return adminCouponService.create(toCommand(request));
    }

    @PutMapping("/{id}")
    public CouponDto update(@PathVariable Long id, @Valid @RequestBody CouponRequest request) {
        return adminCouponService.update(id, toCommand(request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        adminCouponService.delete(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/toggle-status")
    public CouponDto toggleStatus(@PathVariable Long id) {
        return adminCouponService.toggleStatus(id);
    }

    private CouponCommand toCommand(CouponRequest r) {
        return new CouponCommand(
                r.code(), r.name(), r.description(), r.type(), r.value(), r.minimumPurchase(), r.maximumDiscount(),
                r.startsAt(), r.expiresAt(), r.usageLimit(), r.usageLimitPerCustomer(), r.active()
        );
    }
}
