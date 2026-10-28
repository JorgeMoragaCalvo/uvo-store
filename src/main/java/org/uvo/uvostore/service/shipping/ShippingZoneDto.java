package org.uvo.uvostore.service.shipping;

import java.util.List;

/**
 * @param unrecognized las regiones o comunas que el catálogo no reconoce. Solo las tienen zonas escritas a mano
 *                     antes del catálogo; no cuentan para la cobertura ni el cobro hasta que la zona se edite.
 */
public record ShippingZoneDto(
        Long id, String name, String description, List<String> regions, List<String> communes,
        boolean active, int sortOrder, List<String> unrecognized
) {
}
