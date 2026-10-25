package org.uvo.uvostore.service.shipping;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.uvo.uvostore.entity.audit.enums.AuditAction;
import org.uvo.uvostore.entity.audit.enums.AuditTargetType;
import org.uvo.uvostore.entity.shipping.ShippingMethod;
import org.uvo.uvostore.entity.shipping.ShippingRate;
import org.uvo.uvostore.entity.shipping.ShippingZone;
import org.uvo.uvostore.entity.shipping.enums.RateType;
import org.uvo.uvostore.repository.ShippingMethodRepository;
import org.uvo.uvostore.repository.ShippingRateRepository;
import org.uvo.uvostore.repository.ShippingZoneRepository;
import org.uvo.uvostore.security.TenantContext;
import org.uvo.uvostore.service.audit.AuditDiff;
import org.uvo.uvostore.service.audit.AuditEntry;
import org.uvo.uvostore.service.audit.AuditTrail;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

@Service
public class AdminShippingRateServiceImpl implements AdminShippingRateService {

    private final ShippingRateRepository shippingRateRepository;
    private final ShippingMethodRepository shippingMethodRepository;
    private final ShippingZoneRepository shippingZoneRepository;
    private final AuditTrail auditTrail;

    public AdminShippingRateServiceImpl(ShippingRateRepository shippingRateRepository, ShippingMethodRepository shippingMethodRepository,
                                         ShippingZoneRepository shippingZoneRepository, AuditTrail auditTrail) {
        this.shippingRateRepository = shippingRateRepository;
        this.shippingMethodRepository = shippingMethodRepository;
        this.shippingZoneRepository = shippingZoneRepository;
        this.auditTrail = auditTrail;
    }

    @Override
    @Transactional(readOnly = true)
    public List<ShippingRateDto> list(Long methodId, Long zoneId) {
        return shippingRateRepository.findByStoreId(TenantContext.requireStoreId()).stream()
                .filter(r -> methodId == null || r.getMethod().getId().equals(methodId))
                .filter(r -> zoneId == null || r.getZone().getId().equals(zoneId))
                .sorted(Comparator.comparingInt(ShippingRate::getSortOrder))
                .map(this::toDto)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public ShippingRateDto getById(Long id) {
        return toDto(findOrThrow(id));
    }

    @Override
    @Transactional
    public ShippingRateDto create(ShippingRateCommand command) {
        ShippingRate rate = new ShippingRate();
        rate.setStore(TenantContext.requireCurrent());
        applyCommonFields(rate, command);
        ShippingRate saved = shippingRateRepository.save(rate);
        auditTrail.record(AuditEntry.of(AuditAction.SHIPPING_RATE_CREATE, AuditTargetType.SHIPPING_RATE, saved.getId())
                .details(AuditDiff.create().withAll(auditFields(saved))));
        return toDto(saved);
    }

    @Override
    @Transactional
    public ShippingRateDto update(Long id, ShippingRateCommand command) {
        ShippingRate rate = findOrThrow(id);
        Map<String, Object> before = auditFields(rate);
        applyCommonFields(rate, command);
        ShippingRate saved = shippingRateRepository.save(rate);
        AuditDiff diff = AuditDiff.create().values(before, auditFields(saved));
        if (diff.hasChanges()) {
            auditTrail.record(AuditEntry.of(AuditAction.SHIPPING_RATE_UPDATE, AuditTargetType.SHIPPING_RATE, saved.getId())
                    .details(diff.with("rate", saved.getName())));
        }
        return toDto(saved);
    }

    @Override
    @Transactional
    public void delete(Long id) {
        ShippingRate rate = findOrThrow(id);
        auditTrail.record(AuditEntry.of(AuditAction.SHIPPING_RATE_DELETE, AuditTargetType.SHIPPING_RATE, rate.getId())
                .details(AuditDiff.create().withAll(auditFields(rate))));
        shippingRateRepository.delete(rate);
    }

    @Override
    @Transactional
    public ShippingRateDto toggleStatus(Long id) {
        ShippingRate rate = findOrThrow(id);
        rate.setActive(!rate.isActive());
        ShippingRate saved = shippingRateRepository.save(rate);
        auditTrail.record(AuditEntry.of(AuditAction.SHIPPING_RATE_STATUS_TOGGLE, AuditTargetType.SHIPPING_RATE, saved.getId())
                .details(AuditDiff.create().with("rate", saved.getName()).value("active", !saved.isActive(), saved.isActive())));
        return toDto(saved);
    }

    // PROD-18. Todo lo que decide cuánto cobra la tarifa y a quién se le aplica.
    private static Map<String, Object> auditFields(ShippingRate r) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("methodId", r.getMethod() == null ? null : r.getMethod().getId());
        fields.put("zoneId", r.getZone() == null ? null : r.getZone().getId());
        fields.put("name", r.getName());
        fields.put("rateType", r.getRateType());
        fields.put("flatRate", r.getFlatRate());
        fields.put("weightRatePerKg", r.getWeightRatePerKg());
        fields.put("baseWeightRate", r.getBaseWeightRate());
        fields.put("minOrderAmount", r.getMinOrderAmount());
        fields.put("maxOrderAmount", r.getMaxOrderAmount());
        fields.put("minWeight", r.getMinWeight());
        fields.put("maxWeight", r.getMaxWeight());
        fields.put("freeShippingThreshold", r.getFreeShippingThreshold());
        fields.put("active", r.isActive());
        fields.put("sortOrder", r.getSortOrder());
        return fields;
    }

    private void applyCommonFields(ShippingRate rate, ShippingRateCommand command) {
        Long storeId = TenantContext.requireStoreId();
        ShippingMethod method = shippingMethodRepository.findById(command.methodId())
                .filter(m -> m.getStore().getId().equals(storeId))
                .orElseThrow(() -> new NoSuchElementException("Shipping method " + command.methodId() + " not found"));
        ShippingZone zone = shippingZoneRepository.findById(command.zoneId())
                .filter(z -> z.getStore().getId().equals(storeId))
                .orElseThrow(() -> new NoSuchElementException("Shipping zone " + command.zoneId() + " not found"));

        rate.setMethod(method);
        rate.setZone(zone);
        rate.setName(command.name());
        rate.setRateType(RateType.valueOf(command.rateType().toUpperCase()));
        rate.setFlatRate(command.flatRate());
        rate.setWeightRatePerKg(command.weightRatePerKg());
        rate.setBaseWeightRate(command.baseWeightRate());
        rate.setMinOrderAmount(command.minOrderAmount());
        rate.setMaxOrderAmount(command.maxOrderAmount());
        rate.setMinWeight(command.minWeight());
        rate.setMaxWeight(command.maxWeight());
        rate.setFreeShippingThreshold(command.freeShippingThreshold());
        rate.setActive(command.active());
        rate.setSortOrder(command.sortOrder());
    }

    private ShippingRate findOrThrow(Long id) {
        return shippingRateRepository.findById(id)
                .filter(r -> r.getStore().getId().equals(TenantContext.requireStoreId()))
                .orElseThrow(() -> new NoSuchElementException("Shipping rate " + id + " not found"));
    }

    private ShippingRateDto toDto(ShippingRate r) {
        return new ShippingRateDto(
                r.getId(), r.getMethod().getId(), r.getMethod().getName(), r.getZone().getId(), r.getZone().getName(),
                r.getName(), r.getRateType().name().toLowerCase(), r.getFlatRate(), r.getWeightRatePerKg(), r.getBaseWeightRate(),
                r.getMinOrderAmount(), r.getMaxOrderAmount(), r.getMinWeight(), r.getMaxWeight(), r.getFreeShippingThreshold(),
                r.isActive(), r.getSortOrder()
        );
    }
}
