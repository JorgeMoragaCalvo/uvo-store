package org.uvo.uvostore.service.order;

import org.uvo.uvostore.service.BusinessException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.uvo.uvostore.entity.order.Coupon;
import org.uvo.uvostore.entity.order.enums.CouponType;
import org.uvo.uvostore.repository.CouponRepository;
import org.uvo.uvostore.security.TenantContext;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;

@Service
public class AdminCouponServiceImpl implements AdminCouponService {

    private final CouponRepository couponRepository;

    public AdminCouponServiceImpl(CouponRepository couponRepository) {
        this.couponRepository = couponRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public Page<CouponDto> search(String search, String statusFilter, Pageable pageable) {
        return couponRepository.findAll(buildSpecification(search, statusFilter, TenantContext.requireStoreId()), pageable).map(this::toDto);
    }

    @Override
    @Transactional(readOnly = true)
    public CouponDto getById(Long id) {
        return toDto(findOrThrow(id));
    }

    @Override
    @Transactional
    public CouponDto create(CouponCommand command) {
        String code = command.code().toUpperCase();
        if (couponRepository.existsByStoreIdAndCode(TenantContext.requireStoreId(), code)) {
            throw new BusinessException("Este código ya está en uso.");
        }
        validatePercentage(command);

        Coupon coupon = new Coupon();
        coupon.setStore(TenantContext.requireCurrent());
        applyCommonFields(coupon, command, code);
        return toDto(couponRepository.save(coupon));
    }

    @Override
    @Transactional
    public CouponDto update(Long id, CouponCommand command) {
        Coupon coupon = findOrThrow(id);
        String code = command.code().toUpperCase();
        couponRepository.findByStoreIdAndCode(TenantContext.requireStoreId(), code)
                .filter(existing -> !existing.getId().equals(id))
                .ifPresent(existing -> {
                    throw new BusinessException("Este código ya está en uso.");
                });
        validatePercentage(command);

        applyCommonFields(coupon, command, code);
        return toDto(couponRepository.save(coupon));
    }

    @Override
    @Transactional
    public void delete(Long id) {
        Coupon coupon = findOrThrow(id);
        if (coupon.getTimesUsed() > 0) {
            throw new BusinessException("No se puede eliminar un cupón que ya ha sido usado.");
        }
        couponRepository.delete(coupon);
    }

    @Override
    @Transactional
    public CouponDto toggleStatus(Long id) {
        Coupon coupon = findOrThrow(id);
        coupon.setActive(!coupon.isActive());
        return toDto(couponRepository.save(coupon));
    }

    private void validatePercentage(CouponCommand command) {
        if ("percentage".equalsIgnoreCase(command.type()) && command.value().compareTo(java.math.BigDecimal.valueOf(100)) > 0) {
            throw new IllegalArgumentException("El porcentaje no puede ser mayor a 100.");
        }
    }

    private void applyCommonFields(Coupon coupon, CouponCommand command, String code) {
        coupon.setCode(code);
        coupon.setName(command.name());
        coupon.setDescription(command.description());
        coupon.setType(CouponType.valueOf(command.type().toUpperCase()));
        coupon.setValue(command.value());
        coupon.setMinimumPurchase(command.minimumPurchase());
        coupon.setMaximumDiscount(command.maximumDiscount());
        coupon.setStartsAt(command.startsAt());
        coupon.setExpiresAt(command.expiresAt());
        coupon.setUsageLimit(command.usageLimit());
        coupon.setUsageLimitPerCustomer(command.usageLimitPerCustomer());
        coupon.setActive(command.active());
    }

    private Specification<Coupon> buildSpecification(String search, String statusFilter, Long storeId) {
        List<Specification<Coupon>> specs = new ArrayList<>();
        specs.add((root, query, cb) -> cb.equal(root.get("store").get("id"), storeId));

        if (search != null && !search.isBlank()) {
            String term = "%" + search.toLowerCase() + "%";
            specs.add((root, query, cb) -> cb.or(
                    cb.like(cb.lower(root.get("code")), term),
                    cb.like(cb.lower(root.get("name")), term)
            ));
        }

        if ("active".equals(statusFilter)) {
            Instant now = Instant.now();
            specs.add((root, query, cb) -> cb.and(
                    cb.isTrue(root.get("isActive")),
                    cb.or(cb.isNull(root.get("expiresAt")), cb.greaterThan(root.get("expiresAt"), now))
            ));
        } else if ("inactive".equals(statusFilter)) {
            specs.add((root, query, cb) -> cb.isFalse(root.get("isActive")));
        } else if ("expired".equals(statusFilter)) {
            Instant now = Instant.now();
            specs.add((root, query, cb) -> cb.lessThanOrEqualTo(root.get("expiresAt"), now));
        }

        return specs.stream().reduce(Specification::and).orElse(null);
    }

    private Coupon findOrThrow(Long id) {
        return couponRepository.findById(id)
                .filter(c -> c.getStore().getId().equals(TenantContext.requireStoreId()))
                .orElseThrow(() -> new NoSuchElementException("Coupon " + id + " not found"));
    }

    private CouponDto toDto(Coupon c) {
        return new CouponDto(
                c.getId(), c.getCode(), c.getName(), c.getDescription(), c.getType().name().toLowerCase(),
                c.getValue(), c.getMinimumPurchase(), c.getMaximumDiscount(), c.getStartsAt(), c.getExpiresAt(),
                c.getUsageLimit(), c.getUsageLimitPerCustomer(), c.getTimesUsed(), c.isActive(), c.getCreatedAt()
        );
    }
}
