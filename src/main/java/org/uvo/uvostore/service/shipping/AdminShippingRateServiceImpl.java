package org.uvo.uvostore.service.shipping;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.uvo.uvostore.entity.shipping.ShippingMethod;
import org.uvo.uvostore.entity.shipping.ShippingRate;
import org.uvo.uvostore.entity.shipping.ShippingZone;
import org.uvo.uvostore.entity.shipping.enums.RateType;
import org.uvo.uvostore.repository.ShippingMethodRepository;
import org.uvo.uvostore.repository.ShippingRateRepository;
import org.uvo.uvostore.repository.ShippingZoneRepository;
import org.uvo.uvostore.security.TenantContext;

import java.util.Comparator;
import java.util.List;
import java.util.NoSuchElementException;

@Service
public class AdminShippingRateServiceImpl implements AdminShippingRateService {

    private final ShippingRateRepository shippingRateRepository;
    private final ShippingMethodRepository shippingMethodRepository;
    private final ShippingZoneRepository shippingZoneRepository;

    public AdminShippingRateServiceImpl(ShippingRateRepository shippingRateRepository, ShippingMethodRepository shippingMethodRepository,
                                         ShippingZoneRepository shippingZoneRepository) {
        this.shippingRateRepository = shippingRateRepository;
        this.shippingMethodRepository = shippingMethodRepository;
        this.shippingZoneRepository = shippingZoneRepository;
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
        return toDto(shippingRateRepository.save(rate));
    }

    @Override
    @Transactional
    public ShippingRateDto update(Long id, ShippingRateCommand command) {
        ShippingRate rate = findOrThrow(id);
        applyCommonFields(rate, command);
        return toDto(shippingRateRepository.save(rate));
    }

    @Override
    @Transactional
    public void delete(Long id) {
        shippingRateRepository.delete(findOrThrow(id));
    }

    @Override
    @Transactional
    public ShippingRateDto toggleStatus(Long id) {
        ShippingRate rate = findOrThrow(id);
        rate.setActive(!rate.isActive());
        return toDto(shippingRateRepository.save(rate));
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
