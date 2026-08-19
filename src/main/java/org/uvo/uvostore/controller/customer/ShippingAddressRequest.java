package org.uvo.uvostore.controller.customer;

import jakarta.validation.constraints.NotBlank;

public record ShippingAddressRequest(
        @NotBlank String firstName,
        @NotBlank String lastName,
        String company,
        @NotBlank String addressLine1,
        String addressLine2,
        @NotBlank String city,
        @NotBlank String state,
        @NotBlank String postalCode,
        String country,
        @NotBlank String phone,
        boolean isDefault
) {
}
