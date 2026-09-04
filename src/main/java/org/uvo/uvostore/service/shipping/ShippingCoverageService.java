package org.uvo.uvostore.service.shipping;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.uvo.uvostore.entity.shipping.ShippingZone;
import org.uvo.uvostore.repository.ShippingZoneRepository;
import org.uvo.uvostore.security.TenantContext;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

// A7: zones store `regions` and `communes` as free-text JSON arrays an admin types into a textarea,
// and ShippingRateServiceImpl matches them with an exact List.contains(...). Until now nothing
// exposed those strings to the storefront, so a customer had no way of ever guessing one — which is
// why region/commune always arrived null and shipping always came out free.
//
// This flattens the store's ACTIVE zones into the list of regions it delivers to, so the checkout
// can offer exactly those and nothing else. Inactive zones are excluded: matching skips them too.
@Service
public class ShippingCoverageService {

    private final ShippingZoneRepository zoneRepository;

    public ShippingCoverageService(ShippingZoneRepository zoneRepository) {
        this.zoneRepository = zoneRepository;
    }

    @Transactional(readOnly = true)
    public List<ShippingCoverageDto> getCoverage() {
        // Insertion-ordered so regions come out in the zones' own sortOrder, the sequence the admin
        // arranged them in.
        Map<String, LinkedHashSet<String>> byRegion = new LinkedHashMap<>();
        // A region covered by at least one zone with no commune list is covered entirely, so no
        // commune needs picking — even if another zone also lists specific communes inside it.
        // Reporting the merged commune list there would make the checkout demand a choice the
        // matching logic doesn't actually require.
        Set<String> fullyCoveredRegions = new HashSet<>();

        for (ShippingZone zone : zoneRepository.findByStoreIdAndIsActiveTrueOrderBySortOrderAsc(TenantContext.requireStoreId())) {
            if (zone.getRegions() == null) {
                continue;
            }
            boolean wholeRegion = zone.getCommunes() == null || zone.getCommunes().isEmpty();
            for (String region : zone.getRegions()) {
                if (region == null || region.isBlank()) {
                    continue;
                }
                LinkedHashSet<String> communes = byRegion.computeIfAbsent(region, k -> new LinkedHashSet<>());
                if (wholeRegion) {
                    fullyCoveredRegions.add(region);
                } else {
                    zone.getCommunes().stream().filter(c -> c != null && !c.isBlank()).forEach(communes::add);
                }
            }
        }

        List<ShippingCoverageDto> coverage = new ArrayList<>();
        byRegion.forEach((region, communes) -> coverage.add(new ShippingCoverageDto(
                region, fullyCoveredRegions.contains(region) ? List.of() : List.copyOf(communes))));
        return coverage;
    }
}
