package org.uvo.uvostore.controller.order;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;

public record CheckoutRequest(
        @Valid @NotNull CheckoutCustomerRequest customer,
        @Valid @NotNull CheckoutAddressRequest shippingAddress,
        String region,
        String commune,
        @NotEmpty @Valid List<CartItemRequest> items,
        String couponCode,
        @NotBlank @Pattern(regexp = "manual|stripe|webpay|mercadopago") String paymentMethod,
        @Size(max = 1000) String customerNotes,
        // GAP-07: el total que el comprador vio al confirmar. Si el servidor cotiza otro, el checkout responde 409 en
        // vez de cobrar una diferencia que nadie aceptó. Opcional para los clientes de la API anteriores; el SPA
        // siempre lo manda.
        @PositiveOrZero BigDecimal expectedTotal
) {
}
