package org.uvo.uvostore.service.order;

import java.math.BigDecimal;
import java.util.List;

public record CheckoutCommand(
        String customerEmail,
        String customerFirstName,
        String customerLastName,
        String customerPhone,
        CheckoutAddressCommand shippingAddress,
        String region,
        String commune,
        List<CartLineCommand> lines,
        String couponCode,
        String paymentMethod,
        String customerNotes,
        // GAP-07: el total que el comprador vio, o null si el cliente no lo manda.
        BigDecimal expectedTotal
) {
}
