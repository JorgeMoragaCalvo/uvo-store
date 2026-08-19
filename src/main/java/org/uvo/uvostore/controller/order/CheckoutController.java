package org.uvo.uvostore.controller.order;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.uvo.uvostore.service.order.CartLineCommand;
import org.uvo.uvostore.service.order.CheckoutAddressCommand;
import org.uvo.uvostore.service.order.CheckoutCommand;
import org.uvo.uvostore.service.order.CheckoutConfigDto;
import org.uvo.uvostore.service.order.CheckoutService;
import org.uvo.uvostore.service.order.OrderConfirmation;

import java.util.List;

@RestController
@RequestMapping("/api/v1")
public class CheckoutController {

    private final CheckoutService checkoutService;

    public CheckoutController(CheckoutService checkoutService) {
        this.checkoutService = checkoutService;
    }

    @PostMapping("/checkout")
    public OrderConfirmation store(@Valid @RequestBody CheckoutRequest request) {
        CheckoutAddressCommand address = new CheckoutAddressCommand(
                request.shippingAddress().addressLine1(), request.shippingAddress().addressLine2(),
                request.shippingAddress().city(), request.shippingAddress().state(),
                request.shippingAddress().postalCode(), request.shippingAddress().country()
        );

        List<CartLineCommand> lines = request.items().stream()
                .map(item -> "variation".equals(item.type())
                        ? new CartLineCommand(null, item.id(), item.quantity())
                        : new CartLineCommand(item.id(), null, item.quantity()))
                .toList();

        CheckoutCommand command = new CheckoutCommand(
                request.customer().email(), request.customer().firstName(), request.customer().lastName(),
                request.customer().phone(), address, request.region(), request.commune(), lines,
                request.couponCode(), request.paymentMethod(), request.customerNotes()
        );

        return checkoutService.checkout(command);
    }

    @GetMapping("/checkout/config")
    public CheckoutConfigDto config() {
        return checkoutService.getConfig();
    }
}
