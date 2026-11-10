package org.uvo.uvostore.service.shipping;

import org.uvo.uvostore.entity.shipping.enums.ShippingCarrier;

// GAP-18. Proyección de ShippingMethodRepository.findActiveRowsForStore: lee las columnas sin cargar la
// entidad, porque cargarla descifra api_credentials, y la política de envíos es una página pública.
public record ActiveShippingMethodRow(
        Long id,
        String name,
        String description,
        Integer minDeliveryDays,
        Integer maxDeliveryDays,
        boolean hasApiIntegration,
        ShippingCarrier carrier
) {
}
