package org.uvo.uvostore.controller.customer;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.uvo.uvostore.security.AuthPrincipal;
import org.uvo.uvostore.service.customer.CustomerDto;
import org.uvo.uvostore.service.customer.CustomerService;
import org.uvo.uvostore.service.customer.PasswordUpdateCommand;
import org.uvo.uvostore.service.customer.ProfileUpdateCommand;

@io.swagger.v3.oas.annotations.tags.Tag(name = "Cuenta (cliente)", description = "Perfil y contraseña del comprador, JWT bearer con rol CUSTOMER")
@io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/api/customer/account")
public class CustomerAccountController {

    private final CustomerService customerService;

    public CustomerAccountController(CustomerService customerService) {
        this.customerService = customerService;
    }

    @GetMapping
    public CustomerDto profile(Authentication authentication) {
        return customerService.getProfile(customerId(authentication));
    }

    @PutMapping
    public CustomerDto updateProfile(Authentication authentication, @Valid @RequestBody ProfileUpdateRequest request) {
        ProfileUpdateCommand command = new ProfileUpdateCommand(request.firstName(), request.lastName(), request.phone(), request.email());
        return customerService.updateProfile(customerId(authentication), command);
    }

    @PutMapping("/password")
    public ResponseEntity<Void> updatePassword(Authentication authentication, @Valid @RequestBody PasswordUpdateRequest request) {
        PasswordUpdateCommand command = new PasswordUpdateCommand(request.currentPassword(), request.newPassword());
        customerService.updatePassword(customerId(authentication), command);
        return ResponseEntity.noContent().build();
    }

    private Long customerId(Authentication authentication) {
        return ((AuthPrincipal) authentication.getPrincipal()).id();
    }
}
