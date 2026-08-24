package org.uvo.uvostore.service.shipping;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.uvo.uvostore.entity.shipping.ShippingZone;
import org.uvo.uvostore.repository.OrderRepository;
import org.uvo.uvostore.repository.ShippingZoneRepository;
import org.uvo.uvostore.security.TenantContext;

import java.util.Comparator;
import java.util.List;
import java.util.NoSuchElementException;

@Service
public class AdminShippingZoneServiceImpl implements AdminShippingZoneService {

    private final ShippingZoneRepository shippingZoneRepository;
    private final OrderRepository orderRepository;

    public AdminShippingZoneServiceImpl(ShippingZoneRepository shippingZoneRepository, OrderRepository orderRepository) {
        this.shippingZoneRepository = shippingZoneRepository;
        this.orderRepository = orderRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public List<ShippingZoneDto> list(String search) {
        return shippingZoneRepository.findByStoreId(TenantContext.requireStoreId()).stream()
                .filter(z -> search == null || search.isBlank() || containsIgnoreCase(z.getName(), search))
                .sorted(Comparator.comparingInt(ShippingZone::getSortOrder))
                .map(this::toDto)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public ShippingZoneDto getById(Long id) {
        return toDto(findOrThrow(id));
    }

    @Override
    @Transactional
    public ShippingZoneDto create(ShippingZoneCommand command) {
        ShippingZone zone = new ShippingZone();
        zone.setStore(TenantContext.requireCurrent());
        applyCommonFields(zone, command);
        return toDto(shippingZoneRepository.save(zone));
    }

    @Override
    @Transactional
    public ShippingZoneDto update(Long id, ShippingZoneCommand command) {
        ShippingZone zone = findOrThrow(id);
        applyCommonFields(zone, command);
        return toDto(shippingZoneRepository.save(zone));
    }

    @Override
    @Transactional
    public void delete(Long id) {
        ShippingZone zone = findOrThrow(id);
        if (orderRepository.countByShippingZoneId(id) > 0) {
            throw new IllegalStateException("No se puede eliminar una zona con órdenes asociadas.");
        }
        shippingZoneRepository.delete(zone);
    }

    @Override
    @Transactional
    public ShippingZoneDto toggleStatus(Long id) {
        ShippingZone zone = findOrThrow(id);
        zone.setActive(!zone.isActive());
        return toDto(shippingZoneRepository.save(zone));
    }

    private void applyCommonFields(ShippingZone zone, ShippingZoneCommand command) {
        zone.setName(command.name());
        zone.setDescription(command.description());
        zone.setRegions(command.regions());
        zone.setCommunes(command.communes());
        zone.setActive(command.active());
        zone.setSortOrder(command.sortOrder());
    }

    private boolean containsIgnoreCase(String value, String term) {
        return value != null && value.toLowerCase().contains(term.toLowerCase());
    }

    private ShippingZone findOrThrow(Long id) {
        return shippingZoneRepository.findById(id)
                .filter(z -> z.getStore().getId().equals(TenantContext.requireStoreId()))
                .orElseThrow(() -> new NoSuchElementException("Shipping zone " + id + " not found"));
    }

    private ShippingZoneDto toDto(ShippingZone z) {
        return new ShippingZoneDto(z.getId(), z.getName(), z.getDescription(), z.getRegions(), z.getCommunes(), z.isActive(), z.getSortOrder());
    }
}
