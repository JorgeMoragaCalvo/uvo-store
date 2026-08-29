package org.uvo.uvostore.controller.customer;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.uvo.uvostore.security.AuthPrincipal;
import org.uvo.uvostore.service.customer.CustomerAddressService;
import org.uvo.uvostore.service.customer.ShippingAddressCommand;
import org.uvo.uvostore.service.customer.ShippingAddressDto;

import java.util.List;

@io.swagger.v3.oas.annotations.tags.Tag(name = "Direcciones (cliente)", description = "CRUD de direcciones de envío del comprador, JWT bearer con rol CUSTOMER")
@io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/api/customer/addresses")
public class CustomerAddressController {

    private final CustomerAddressService customerAddressService;

    public CustomerAddressController(CustomerAddressService customerAddressService) {
        this.customerAddressService = customerAddressService;
    }

    @GetMapping
    public List<ShippingAddressDto> index(Authentication authentication) {
        return customerAddressService.listAddresses(customerId(authentication));
    }

    @PostMapping
    public ShippingAddressDto create(Authentication authentication, @Valid @RequestBody ShippingAddressRequest request) {
        return customerAddressService.createAddress(customerId(authentication), toCommand(request));
    }

    @PutMapping("/{addressId}")
    public ShippingAddressDto update(Authentication authentication, @PathVariable Long addressId, @Valid @RequestBody ShippingAddressRequest request) {
        return customerAddressService.updateAddress(customerId(authentication), addressId, toCommand(request));
    }

    @DeleteMapping("/{addressId}")
    public ResponseEntity<Void> delete(Authentication authentication, @PathVariable Long addressId) {
        customerAddressService.deleteAddress(customerId(authentication), addressId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{addressId}/default")
    public ShippingAddressDto setDefault(Authentication authentication, @PathVariable Long addressId) {
        return customerAddressService.setDefaultAddress(customerId(authentication), addressId);
    }

    private ShippingAddressCommand toCommand(ShippingAddressRequest request) {
        return new ShippingAddressCommand(
                request.firstName(), request.lastName(), request.company(), request.addressLine1(),
                request.addressLine2(), request.city(), request.state(), request.postalCode(),
                request.country(), request.phone(), request.isDefault()
        );
    }

    private Long customerId(Authentication authentication) {
        return ((AuthPrincipal) authentication.getPrincipal()).id();
    }
}
