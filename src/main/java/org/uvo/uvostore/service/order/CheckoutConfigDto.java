package org.uvo.uvostore.service.order;

import java.math.BigDecimal;

public record CheckoutConfigDto(
        String stripePublicKey,
        boolean stripeEnabled,
        boolean webpayEnabled,
        boolean mercadopagoEnabled,
        boolean shippingEnabled,
        BigDecimal defaultShippingCost,
        boolean freeShippingEnabled,
        BigDecimal freeShippingThreshold,
        boolean allowGuestCheckout,
        boolean requirePhone,
        BigDecimal taxRate,
        String currency,
        String currencySymbol,
        // IMP-07. El pago manual: los datos de la transferencia y el plazo para pagar, o null si la tienda no la
        // ofrece (entonces el checkout no muestra la opción).
        org.uvo.uvostore.service.payment.transfer.BankTransferDto bankTransfer
) {
}
