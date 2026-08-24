package org.uvo.uvostore.entity.shipping.enums;

// Quote-only carrier integrations (Fase 3): the store configures a ShippingMethod with
// hasApiIntegration=true and one of these, and checkout asks the carrier for a live rate instead
// of reading from the static ShippingRate table. See service.shipping.carrier.
public enum ShippingCarrier {
    CHILEXPRESS,
    CORREOS_CHILE
}
