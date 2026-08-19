package org.uvo.uvostore.service.customer;

public record ShippingAddressDto(
        Long id,
        String firstName,
        String lastName,
        String company,
        String addressLine1,
        String addressLine2,
        String city,
        String state,
        String postalCode,
        String country,
        String phone,
        boolean isDefault
) {
}
