package org.uvo.uvostore.controller.order;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.uvo.uvostore.service.order.CartCalculationResult;
import org.uvo.uvostore.service.order.CartItemCommand;
import org.uvo.uvostore.service.order.CartService;
import org.uvo.uvostore.service.order.CartValidationResult;

@io.swagger.v3.oas.annotations.tags.Tag(name = "Carrito (público)", description = "Validación y cálculo del carrito del storefront, sin autenticación")
@RestController
@RequestMapping("/api/v1/cart")
public class CartController {

    private final CartService cartService;

    public CartController(CartService cartService) {
        this.cartService = cartService;
    }

    @PostMapping("/validate")
    public CartValidationResult validate(@Valid @RequestBody CartValidateRequest request) {
        return cartService.validateItems(toCommands(request.items()));
    }

    @PostMapping("/calculate")
    public CartCalculationResult calculate(@Valid @RequestBody CartCalculateRequest request) {
        return cartService.calculate(toCommands(request.items()), request.region(), request.commune(), request.couponCode());
    }

    private java.util.List<CartItemCommand> toCommands(java.util.List<CartItemRequest> items) {
        return items.stream().map(i -> new CartItemCommand(i.id(), i.type(), i.quantity())).toList();
    }
}
