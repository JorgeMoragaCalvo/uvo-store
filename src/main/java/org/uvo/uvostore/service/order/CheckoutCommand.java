package org.uvo.uvostore.service.order;

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
        String customerNotes
) {
}
