package org.uvo.uvostore.service.shipping.carrier;

import java.math.BigDecimal;

// originCommune comes from the store's own configured pickup address (Setting rows
// "shipping_origin_region"/"shipping_origin_commune"), not from the buyer — carriers quote a
// price for a specific origin->destination pair, not just a destination.
public record ShippingCarrierQuoteRequest(
        String originRegion,
        String originCommune,
        String destinationRegion,
        String destinationCommune,
        BigDecimal totalWeightKg,
        BigDecimal declaredValue
) {
}
