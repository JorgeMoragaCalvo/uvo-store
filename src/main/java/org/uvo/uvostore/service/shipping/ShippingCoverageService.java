package org.uvo.uvostore.service.shipping;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.uvo.uvostore.entity.shipping.ShippingZone;
import org.uvo.uvostore.repository.ShippingZoneRepository;
import org.uvo.uvostore.security.TenantContext;
import org.uvo.uvostore.service.geo.GeoCatalog;

import java.text.Collator;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

// A7: zones store `regions` and `communes` as JSON arrays, and until A7 nothing exposed them to the
// storefront, so a customer had no way of ever guessing one — which is why region/commune always
// arrived null and shipping always came out free.
//
// This flattens the store's ACTIVE zones into the regions it delivers to, so the checkout can offer
// exactly those and nothing else. Inactive zones are excluded: matching skips them too.
//
// Catálogo de comunas: cada región trae siempre las comunas que el comprador puede elegir, en su forma
// canónica. Antes, una región cubierta entera llegaba sin comunas y el checkout no pedía ninguna; si en
// esa región había además una zona por comunas, el cálculo, sin comuna, podía cobrarle a toda la región
// la tarifa de esa zona.
@Service
public class ShippingCoverageService {

    private final ShippingZoneRepository zoneRepository;
    private final ZoneGeography zoneGeography;
    private final GeoCatalog geoCatalog;

    public ShippingCoverageService(ShippingZoneRepository zoneRepository, ZoneGeography zoneGeography,
                                   GeoCatalog geoCatalog) {
        this.zoneRepository = zoneRepository;
        this.zoneGeography = zoneGeography;
        this.geoCatalog = geoCatalog;
    }

    @Transactional(readOnly = true)
    public List<ShippingCoverageDto> getCoverage() {
        // Insertion-ordered so regions come out in the zones' own sortOrder, the sequence the admin
        // arranged them in.
        Map<String, Set<String>> byRegion = new LinkedHashMap<>();
        // Una región que alguna zona cubre entera ofrece todas sus comunas, aunque otra zona nombre
        // algunas: esas cobran su tarifa propia, y el resto, la de la región entera.
        Set<String> fullyCoveredRegions = new HashSet<>();

        for (ShippingZone zone : zoneRepository.findByStoreIdAndIsActiveTrueOrderBySortOrderAsc(TenantContext.requireStoreId())) {
            zoneGeography.coverage(zone).forEach((region, communes) -> {
                Set<String> named = byRegion.computeIfAbsent(region, k -> new LinkedHashSet<>());
                if (communes.isEmpty()) {
                    fullyCoveredRegions.add(region);
                } else {
                    named.addAll(communes);
                }
            });
        }

        Collator spanish = Collator.getInstance(Locale.forLanguageTag("es"));
        List<ShippingCoverageDto> coverage = new ArrayList<>();
        byRegion.forEach((region, named) -> {
            List<String> communes = new ArrayList<>(
                    fullyCoveredRegions.contains(region) ? geoCatalog.communesOf(region) : named);
            communes.sort(spanish);
            coverage.add(new ShippingCoverageDto(region, List.copyOf(communes)));
        });
        return coverage;
    }
}
