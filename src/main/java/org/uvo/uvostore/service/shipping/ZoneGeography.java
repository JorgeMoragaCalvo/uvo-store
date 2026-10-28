package org.uvo.uvostore.service.shipping;

import org.springframework.stereotype.Component;
import org.uvo.uvostore.entity.shipping.ShippingZone;
import org.uvo.uvostore.service.geo.GeoCatalog;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Qué cubre una zona de envío, leída contra el catálogo de regiones y comunas.
 *
 * <p>La cobertura que ve el comprador y el cálculo que cobra leen las zonas solo por aquí, para que no vuelvan a
 * discrepar. Antes cada uno tenía su propia regla y la lista de comunas era una sola para todas las regiones de
 * la zona: una zona de Metropolitana y Valparaíso que nombraba «Santiago» dejaba Valparaíso sin ninguna comuna
 * que coincidiera. Ahora cada región se lee con sus propias comunas, y una región sin ninguna nombrada se cubre
 * entera.
 */
@Component
public class ZoneGeography {

    private final GeoCatalog geoCatalog;

    public ZoneGeography(GeoCatalog geoCatalog) {
        this.geoCatalog = geoCatalog;
    }

    /**
     * Región canónica → comunas canónicas de la zona en esa región, en el orden de la zona. Un conjunto vacío
     * es la región entera. Lo que no se reconoce queda fuera (ver {@link #unrecognized}).
     */
    public Map<String, Set<String>> coverage(ShippingZone zone) {
        Map<String, Set<String>> coverage = new LinkedHashMap<>();
        if (zone.getRegions() == null) {
            return coverage;
        }
        for (String rawRegion : zone.getRegions()) {
            geoCatalog.resolveRegion(rawRegion).ifPresent(region -> {
                Set<String> communes = coverage.computeIfAbsent(region, k -> new LinkedHashSet<>());
                if (zone.getCommunes() != null) {
                    for (String rawCommune : zone.getCommunes()) {
                        geoCatalog.resolveCommune(region, rawCommune).ifPresent(communes::add);
                    }
                }
            });
        }
        return coverage;
    }

    /** Las regiones y comunas de la zona que el catálogo no reconoce: zonas escritas a mano antes de él. */
    public List<String> unrecognized(ShippingZone zone) {
        List<String> unrecognized = new ArrayList<>();
        List<String> regions = new ArrayList<>();
        if (zone.getRegions() != null) {
            for (String rawRegion : zone.getRegions()) {
                Optional<String> region = geoCatalog.resolveRegion(rawRegion);
                region.ifPresentOrElse(regions::add, () -> unrecognized.add(rawRegion));
            }
        }
        if (zone.getCommunes() != null) {
            for (String rawCommune : zone.getCommunes()) {
                boolean known = regions.stream().anyMatch(r -> geoCatalog.resolveCommune(r, rawCommune).isPresent());
                if (!known) {
                    unrecognized.add(rawCommune);
                }
            }
        }
        return unrecognized;
    }
}
