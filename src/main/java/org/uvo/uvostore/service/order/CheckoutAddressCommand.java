package org.uvo.uvostore.service.order;

public record CheckoutAddressCommand(
        String addressLine1,
        String addressLine2,
        String city,
        String state,
        String postalCode,
        String country
) {
}
