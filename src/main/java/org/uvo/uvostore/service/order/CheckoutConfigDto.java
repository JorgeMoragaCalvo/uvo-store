package org.uvo.uvostore.service.order;

import java.math.BigDecimal;

public record CheckoutConfigDto(
        String stripePublicKey,
        boolean stripeEnabled,
        boolean shippingEnabled,
        BigDecimal defaultShippingCost,
        boolean freeShippingEnabled,
        BigDecimal freeShippingThreshold,
        boolean allowGuestCheckout,
        boolean requirePhone,
        BigDecimal taxRate,
        String currency,
        String currencySymbol
) {
}
