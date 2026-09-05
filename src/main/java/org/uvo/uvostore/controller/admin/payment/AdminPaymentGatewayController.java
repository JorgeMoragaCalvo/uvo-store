package org.uvo.uvostore.controller.admin.payment;

import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.uvo.uvostore.entity.payment.enums.PaymentGatewayType;
import org.uvo.uvostore.service.payment.AdminPaymentGatewayService;
import org.uvo.uvostore.service.payment.PaymentGatewayConfigCommand;
import org.uvo.uvostore.service.payment.PaymentGatewayConfigDto;

import java.util.List;

@io.swagger.v3.oas.annotations.tags.Tag(name = "Pasarelas de pago (admin)", description = "Configuración de Webpay/MercadoPago por tienda, JWT bearer con rol ADMIN")
@io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/api/admin/payment-gateways")
public class AdminPaymentGatewayController {

    private final AdminPaymentGatewayService service;

    public AdminPaymentGatewayController(AdminPaymentGatewayService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('payments.view')")
    public List<PaymentGatewayConfigDto> index() {
        return service.list();
    }

    @PutMapping("/{gateway}")
    @PreAuthorize("hasAuthority('payments.manage')")
    public PaymentGatewayConfigDto update(@PathVariable PaymentGatewayType gateway, @Valid @RequestBody PaymentGatewayConfigCommand command) {
        return service.upsert(gateway, command);
    }
}
