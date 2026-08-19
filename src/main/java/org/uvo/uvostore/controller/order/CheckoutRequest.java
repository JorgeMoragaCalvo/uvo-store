package org.uvo.uvostore.controller.order;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

public record CheckoutRequest(
        @Valid @NotNull CheckoutCustomerRequest customer,
        @Valid @NotNull CheckoutAddressRequest shippingAddress,
        String region,
        String commune,
        @NotEmpty @Valid List<CartItemRequest> items,
        String couponCode,
        @NotBlank @Pattern(regexp = "manual|stripe") String paymentMethod,
        @Size(max = 1000) String customerNotes
) {
}
