package org.uvo.uvostore.service.shipping;

import org.uvo.uvostore.service.BusinessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.uvo.uvostore.entity.shipping.ShippingZone;
import org.uvo.uvostore.repository.OrderRepository;
import org.uvo.uvostore.repository.ShippingZoneRepository;
import org.uvo.uvostore.security.TenantContext;
import org.uvo.uvostore.service.geo.GeoCatalog;

import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;

@Service
public class AdminShippingZoneServiceImpl implements AdminShippingZoneService {

    private final ShippingZoneRepository shippingZoneRepository;
    private final OrderRepository orderRepository;
    private final GeoCatalog geoCatalog;
    private final ZoneGeography zoneGeography;

    public AdminShippingZoneServiceImpl(ShippingZoneRepository shippingZoneRepository, OrderRepository orderRepository,
                                        GeoCatalog geoCatalog, ZoneGeography zoneGeography) {
        this.shippingZoneRepository = shippingZoneRepository;
        this.orderRepository = orderRepository;
        this.geoCatalog = geoCatalog;
        this.zoneGeography = zoneGeography;
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
            throw new BusinessException("No se puede eliminar una zona con órdenes asociadas.");
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
        // Catálogo de comunas: se guarda el nombre canónico y se rechaza lo que no existe. Antes se guardaba lo
        // que se escribiera, y una región mal escrita dejaba la zona sin cubrir nada sin que nadie lo notara.
        List<String> regions = canonicalRegions(command.regions());
        zone.setRegions(regions);
        zone.setCommunes(canonicalCommunes(command.communes(), regions));
        zone.setActive(command.active());
        zone.setSortOrder(command.sortOrder());
    }

    private List<String> canonicalRegions(List<String> regions) {
        if (regions == null) {
            return null;
        }
        Set<String> canonical = new LinkedHashSet<>();
        for (String region : regions) {
            canonical.add(geoCatalog.resolveRegion(region)
                    .orElseThrow(() -> new BusinessException("Región no reconocida: " + region)));
        }
        return List.copyOf(canonical);
    }

    private List<String> canonicalCommunes(List<String> communes, List<String> regions) {
        if (communes == null) {
            return null;
        }
        List<String> zoneRegions = regions == null ? List.of() : regions;
        Set<String> canonical = new LinkedHashSet<>();
        for (String commune : communes) {
            canonical.add(zoneRegions.stream()
                    .map(region -> geoCatalog.resolveCommune(region, commune))
                    .flatMap(Optional::stream)
                    .findFirst()
                    .orElseThrow(() -> new BusinessException(
                            "La comuna " + commune + " no pertenece a ninguna de las regiones de la zona")));
        }
        return List.copyOf(canonical);
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
        // Lo reconocible va en su forma canónica, para que el formulario lo marque en el catálogo aunque la zona
        // se haya escrito a mano («RM», «nunoa»). Lo que no se reconoce queda tal cual y aparte, en unrecognized.
        List<String> regions = z.getRegions() == null ? null : z.getRegions().stream()
                .map(region -> geoCatalog.resolveRegion(region).orElse(region))
                .distinct()
                .toList();
        List<String> communes = z.getCommunes() == null ? null : z.getCommunes().stream()
                .map(commune -> canonicalCommune(commune, regions))
                .distinct()
                .toList();
        return new ShippingZoneDto(z.getId(), z.getName(), z.getDescription(), regions, communes,
                z.isActive(), z.getSortOrder(), zoneGeography.unrecognized(z));
    }

    private String canonicalCommune(String commune, List<String> regions) {
        if (regions == null) {
            return commune;
        }
        return regions.stream()
                .map(region -> geoCatalog.resolveCommune(region, commune))
                .flatMap(Optional::stream)
                .findFirst()
                .orElse(commune);
    }
}
