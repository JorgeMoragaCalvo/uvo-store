package org.uvo.uvostore.service.shipping;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.uvo.uvostore.entity.shipping.ShippingMethod;
import org.uvo.uvostore.entity.shipping.enums.ShippingMethodType;
import org.uvo.uvostore.repository.OrderRepository;
import org.uvo.uvostore.repository.ShippingMethodRepository;
import org.uvo.uvostore.security.TenantContext;

import java.util.Comparator;
import java.util.List;
import java.util.NoSuchElementException;

@Service
public class AdminShippingMethodServiceImpl implements AdminShippingMethodService {

    private final ShippingMethodRepository shippingMethodRepository;
    private final OrderRepository orderRepository;

    public AdminShippingMethodServiceImpl(ShippingMethodRepository shippingMethodRepository, OrderRepository orderRepository) {
        this.shippingMethodRepository = shippingMethodRepository;
        this.orderRepository = orderRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public List<ShippingMethodDto> list() {
        return shippingMethodRepository.findByStoreId(TenantContext.requireStoreId()).stream()
                .sorted(Comparator.comparingInt(ShippingMethod::getSortOrder))
                .map(this::toDto)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public ShippingMethodDto getById(Long id) {
        return toDto(findOrThrow(id));
    }

    @Override
    @Transactional
    public ShippingMethodDto create(ShippingMethodCommand command) {
        ensureCodeAvailable(command.code(), null);
        ShippingMethod method = new ShippingMethod();
        method.setStore(TenantContext.requireCurrent());
        applyCommonFields(method, command);
        return toDto(shippingMethodRepository.save(method));
    }

    @Override
    @Transactional
    public ShippingMethodDto update(Long id, ShippingMethodCommand command) {
        ShippingMethod method = findOrThrow(id);
        ensureCodeAvailable(command.code(), id);
        applyCommonFields(method, command);
        return toDto(shippingMethodRepository.save(method));
    }

    @Override
    @Transactional
    public void delete(Long id) {
        ShippingMethod method = findOrThrow(id);
        if (orderRepository.countByShippingMethodRefId(id) > 0) {
            throw new IllegalStateException("No se puede eliminar un método con órdenes asociadas.");
        }
        shippingMethodRepository.delete(method);
    }

    @Override
    @Transactional
    public ShippingMethodDto toggleStatus(Long id) {
        ShippingMethod method = findOrThrow(id);
        method.setActive(!method.isActive());
        return toDto(shippingMethodRepository.save(method));
    }

    private void ensureCodeAvailable(String code, Long excludingId) {
        shippingMethodRepository.findByStoreIdAndCode(TenantContext.requireStoreId(), code)
                .filter(existing -> excludingId == null || !existing.getId().equals(excludingId))
                .ifPresent(existing -> {
                    throw new IllegalStateException("Este código ya está en uso.");
                });
    }

    private void applyCommonFields(ShippingMethod method, ShippingMethodCommand command) {
        method.setName(command.name());
        method.setCode(command.code());
        method.setDescription(command.description());
        method.setType(ShippingMethodType.valueOf(command.type().toUpperCase()));
        method.setHasApiIntegration(command.hasApiIntegration());
        method.setMinDeliveryDays(command.minDeliveryDays());
        method.setMaxDeliveryDays(command.maxDeliveryDays());
        method.setActive(command.active());
        method.setSortOrder(command.sortOrder());
    }

    private ShippingMethod findOrThrow(Long id) {
        return shippingMethodRepository.findById(id)
                .filter(m -> m.getStore().getId().equals(TenantContext.requireStoreId()))
                .orElseThrow(() -> new NoSuchElementException("Shipping method " + id + " not found"));
    }

    private ShippingMethodDto toDto(ShippingMethod m) {
        return new ShippingMethodDto(
                m.getId(), m.getName(), m.getCode(), m.getDescription(), m.getType().name().toLowerCase(),
                m.isHasApiIntegration(), m.getMinDeliveryDays(), m.getMaxDeliveryDays(), m.isActive(), m.getSortOrder(),
                m.getRates().size()
        );
    }
}
