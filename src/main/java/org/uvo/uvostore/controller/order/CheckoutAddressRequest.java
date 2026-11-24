package org.uvo.uvostore.controller.order;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CheckoutAddressRequest(
        @NotBlank String addressLine1,
        String addressLine2,
        @NotBlank String city,
        @NotBlank String state,
        // Opcional: en Chile casi nadie lo conoce y el envío se cotiza por región y comuna.
        String postalCode,
        @NotBlank @Size(max = 2) String country
) {
}
