package org.uvo.uvostore.service.order;

import org.uvo.uvostore.service.BusinessException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.uvo.uvostore.entity.audit.enums.AuditAction;
import org.uvo.uvostore.entity.audit.enums.AuditTargetType;
import org.uvo.uvostore.entity.order.Coupon;
import org.uvo.uvostore.entity.order.enums.CouponType;
import org.uvo.uvostore.repository.CouponRepository;
import org.uvo.uvostore.security.TenantContext;
import org.uvo.uvostore.service.audit.AuditDiff;
import org.uvo.uvostore.service.audit.AuditEntry;
import org.uvo.uvostore.service.audit.AuditTrail;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

@Service
public class AdminCouponServiceImpl implements AdminCouponService {

    private final CouponRepository couponRepository;
    private final AuditTrail auditTrail;

    public AdminCouponServiceImpl(CouponRepository couponRepository, AuditTrail auditTrail) {
        this.couponRepository = couponRepository;
        this.auditTrail = auditTrail;
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
        Coupon saved = couponRepository.save(coupon);
        auditTrail.record(AuditEntry.of(AuditAction.COUPON_CREATE, AuditTargetType.COUPON, saved.getId())
                .details(AuditDiff.create().withAll(auditFields(saved))));
        return toDto(saved);
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

        Map<String, Object> before = auditFields(coupon);
        String previousDescription = coupon.getDescription();
        applyCommonFields(coupon, command, code);
        Coupon saved = couponRepository.save(coupon);
        AuditDiff diff = AuditDiff.create()
                .values(before, auditFields(saved))
                .opaque("description", previousDescription, saved.getDescription());
        if (diff.hasChanges()) {
            auditTrail.record(AuditEntry.of(AuditAction.COUPON_UPDATE, AuditTargetType.COUPON, saved.getId())
                    .details(diff.with("coupon", saved.getCode())));
        }
        return toDto(saved);
    }

    @Override
    @Transactional
    public void delete(Long id) {
        Coupon coupon = findOrThrow(id);
        if (coupon.getTimesUsed() > 0) {
            throw new BusinessException("No se puede eliminar un cupón que ya ha sido usado.");
        }
        auditTrail.record(AuditEntry.of(AuditAction.COUPON_DELETE, AuditTargetType.COUPON, coupon.getId())
                .details(AuditDiff.create().withAll(auditFields(coupon))));
        couponRepository.delete(coupon);
    }

    @Override
    @Transactional
    public CouponDto toggleStatus(Long id) {
        Coupon coupon = findOrThrow(id);
        coupon.setActive(!coupon.isActive());
        Coupon saved = couponRepository.save(coupon);
        auditTrail.record(AuditEntry.of(AuditAction.COUPON_STATUS_TOGGLE, AuditTargetType.COUPON, saved.getId())
                .details(AuditDiff.create().with("coupon", saved.getCode()).value("active", !saved.isActive(), saved.isActive())));
        return toDto(saved);
    }

    // PROD-18. Lo que decide cuánto descuenta. La descripción es texto libre y va aparte, solo por nombre.
    private static Map<String, Object> auditFields(Coupon c) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("code", c.getCode());
        fields.put("name", c.getName());
        fields.put("type", c.getType());
        fields.put("value", c.getValue());
        fields.put("minimumPurchase", c.getMinimumPurchase());
        fields.put("maximumDiscount", c.getMaximumDiscount());
        fields.put("startsAt", c.getStartsAt());
        fields.put("expiresAt", c.getExpiresAt());
        fields.put("usageLimit", c.getUsageLimit());
        fields.put("usageLimitPerCustomer", c.getUsageLimitPerCustomer());
        fields.put("active", c.isActive());
        return fields;
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
