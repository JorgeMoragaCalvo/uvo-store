package org.uvo.uvostore.service.shipping;

import org.uvo.uvostore.service.BusinessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.uvo.uvostore.entity.audit.enums.AuditAction;
import org.uvo.uvostore.entity.audit.enums.AuditTargetType;
import org.uvo.uvostore.entity.shipping.ShippingMethod;
import org.uvo.uvostore.entity.shipping.enums.ShippingCarrier;
import org.uvo.uvostore.entity.shipping.enums.ShippingMethodType;
import org.uvo.uvostore.repository.OrderRepository;
import org.uvo.uvostore.repository.ShippingMethodRepository;
import org.uvo.uvostore.security.TenantContext;
import org.uvo.uvostore.service.audit.AuditDiff;
import org.uvo.uvostore.service.audit.AuditEntry;
import org.uvo.uvostore.service.audit.AuditTrail;
import org.uvo.uvostore.service.shipping.carrier.ShippingCarrierPolicy;

import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

@Service
public class AdminShippingMethodServiceImpl implements AdminShippingMethodService {

    private final ShippingMethodRepository shippingMethodRepository;
    private final OrderRepository orderRepository;
    private final ShippingCarrierPolicy carrierPolicy;
    private final AuditTrail auditTrail;

    public AdminShippingMethodServiceImpl(ShippingMethodRepository shippingMethodRepository,
                                          OrderRepository orderRepository,
                                          ShippingCarrierPolicy carrierPolicy, AuditTrail auditTrail) {
        this.shippingMethodRepository = shippingMethodRepository;
        this.orderRepository = orderRepository;
        this.carrierPolicy = carrierPolicy;
        this.auditTrail = auditTrail;
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
        ShippingMethod saved = shippingMethodRepository.save(method);
        auditTrail.record(AuditEntry.of(AuditAction.SHIPPING_METHOD_CREATE, AuditTargetType.SHIPPING_METHOD, saved.getId())
                .details(AuditDiff.create().withAll(auditFields(saved))));
        return toDto(saved);
    }

    @Override
    @Transactional
    public ShippingMethodDto update(Long id, ShippingMethodCommand command) {
        ShippingMethod method = findOrThrow(id);
        ensureCodeAvailable(command.code(), id);
        Map<String, Object> before = auditFields(method);
        String previousDescription = method.getDescription();
        applyCommonFields(method, command);
        ShippingMethod saved = shippingMethodRepository.save(method);
        AuditDiff diff = AuditDiff.create()
                .values(before, auditFields(saved))
                .opaque("description", previousDescription, saved.getDescription());
        if (diff.hasChanges()) {
            auditTrail.record(AuditEntry.of(AuditAction.SHIPPING_METHOD_UPDATE, AuditTargetType.SHIPPING_METHOD, saved.getId())
                    .details(diff.with("method", saved.getCode())));
        }
        return toDto(saved);
    }

    @Override
    @Transactional
    public void delete(Long id) {
        ShippingMethod method = findOrThrow(id);
        if (orderRepository.countByShippingMethodRefId(id) > 0) {
            throw new BusinessException("No se puede eliminar un método con órdenes asociadas.");
        }
        auditTrail.record(AuditEntry.of(AuditAction.SHIPPING_METHOD_DELETE, AuditTargetType.SHIPPING_METHOD, method.getId())
                .details(AuditDiff.create().withAll(auditFields(method))));
        shippingMethodRepository.delete(method);
    }

    @Override
    @Transactional
    public ShippingMethodDto toggleStatus(Long id) {
        ShippingMethod method = findOrThrow(id);
        boolean activating = !method.isActive();
        // PROD-12. Las filas escritas antes del guarda de resolveCarrier siguen ahí, y este es el camino
        // por el que se reactivarían. Apagar uno nunca se bloquea: encerrar a alguien con un método
        // inservible encendido sería el fallo opuesto.
        if (activating && method.isHasApiIntegration() && !carrierPolicy.isAvailable(method.getCarrier())) {
            throw new BusinessException(carrierPolicy.unavailableReason(method.getCarrier()));
        }
        method.setActive(activating);
        ShippingMethod saved = shippingMethodRepository.save(method);
        auditTrail.record(AuditEntry.of(AuditAction.SHIPPING_METHOD_STATUS_TOGGLE, AuditTargetType.SHIPPING_METHOD, saved.getId())
                .details(AuditDiff.create().with("method", saved.getCode()).value("active", !activating, activating)));
        return toDto(saved);
    }

    @Override
    @Transactional
    public ShippingMethodDto updateCredentials(Long id, Map<String, String> credentials) {
        ShippingMethod method = findOrThrow(id);
        Map<String, String> previous = new HashMap<>(method.getApiCredentials());
        Map<String, String> merged = new HashMap<>(method.getApiCredentials());
        if (credentials != null) {
            merged.putAll(credentials);
        }
        method.setApiCredentials(merged);
        ShippingMethod saved = shippingMethodRepository.save(method);
        // PROD-18. Solo qué clave se puso, se reemplazó o se borró; ningún valor.
        AuditDiff diff = AuditDiff.create().credentials("credentials", previous, saved.getApiCredentials());
        if (diff.hasChanges()) {
            auditTrail.record(AuditEntry.of(AuditAction.SHIPPING_METHOD_CREDENTIALS_UPDATE, AuditTargetType.SHIPPING_METHOD,
                            saved.getId())
                    .details(diff.with("method", saved.getCode()).with("carrier", saved.getCarrier())));
        }
        return toDto(saved);
    }

    // PROD-18. Lo que decide si el método se ofrece y cómo. Las credenciales nunca: van por updateCredentials.
    private static Map<String, Object> auditFields(ShippingMethod m) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("code", m.getCode());
        fields.put("name", m.getName());
        fields.put("type", m.getType());
        fields.put("carrier", m.getCarrier());
        fields.put("hasApiIntegration", m.isHasApiIntegration());
        fields.put("minDeliveryDays", m.getMinDeliveryDays());
        fields.put("maxDeliveryDays", m.getMaxDeliveryDays());
        fields.put("active", m.isActive());
        fields.put("sortOrder", m.getSortOrder());
        return fields;
    }

    private void ensureCodeAvailable(String code, Long excludingId) {
        shippingMethodRepository.findByStoreIdAndCode(TenantContext.requireStoreId(), code)
                .filter(existing -> excludingId == null || !existing.getId().equals(excludingId))
                .ifPresent(existing -> {
                    throw new BusinessException("Este código ya está en uso.");
                });
    }

    private void applyCommonFields(ShippingMethod method, ShippingMethodCommand command) {
        method.setName(command.name());
        method.setCode(command.code());
        method.setDescription(command.description());
        method.setType(ShippingMethodType.valueOf(command.type().toUpperCase()));
        method.setHasApiIntegration(command.hasApiIntegration());
        method.setCarrier(resolveCarrier(command.carrier()));
        method.setMinDeliveryDays(command.minDeliveryDays());
        method.setMaxDeliveryDays(command.maxDeliveryDays());
        method.setActive(command.active());
        method.setSortOrder(command.sortOrder());
    }

    /**
     * PROD-12. Un transportista que no se puede ofrecer tampoco se puede configurar.
     *
     * <p>Antes esto era un {@code ShippingCarrier.valueOf(...)} suelto: cualquiera de los dos valores del
     * enum se aceptaba y se guardaba. Con Correos de Chile —un placeholder que siempre devuelve vacío— el
     * método quedaba activo y el checkout no lo mostraba nunca, sin que nada avisara al comerciante. Un
     * nombre que no existe en el enum sigue siendo un 400 por {@code IllegalArgumentException} (M1), y
     * ahora uno que existe pero no está disponible también, con un mensaje que dice cuál de los dos
     * problemas es.
     */
    private ShippingCarrier resolveCarrier(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        ShippingCarrier carrier = ShippingCarrier.valueOf(name.toUpperCase());
        if (!carrierPolicy.isAvailable(carrier)) {
            throw new BusinessException(carrierPolicy.unavailableReason(carrier));
        }
        return carrier;
    }

    private ShippingMethod findOrThrow(Long id) {
        return shippingMethodRepository.findById(id)
                .filter(m -> m.getStore().getId().equals(TenantContext.requireStoreId()))
                .orElseThrow(() -> new NoSuchElementException("Shipping method " + id + " not found"));
    }

    private ShippingMethodDto toDto(ShippingMethod m) {
        Map<String, Boolean> credentialsSet = new HashMap<>();
        m.getApiCredentials().forEach((key, value) -> credentialsSet.put(key, value != null && !value.isBlank()));
        return new ShippingMethodDto(
                m.getId(), m.getName(), m.getCode(), m.getDescription(), m.getType().name().toLowerCase(),
                m.isHasApiIntegration(), m.getCarrier() == null ? null : m.getCarrier().name(), credentialsSet,
                m.getMinDeliveryDays(), m.getMaxDeliveryDays(), m.isActive(), m.getSortOrder(),
                m.getRates().size()
        );
    }
}
