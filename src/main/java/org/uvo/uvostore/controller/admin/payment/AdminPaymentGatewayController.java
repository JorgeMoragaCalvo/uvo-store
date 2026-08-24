package org.uvo.uvostore.controller.admin.payment;

import jakarta.validation.Valid;
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

@RestController
@RequestMapping("/api/admin/payment-gateways")
public class AdminPaymentGatewayController {

    private final AdminPaymentGatewayService service;

    public AdminPaymentGatewayController(AdminPaymentGatewayService service) {
        this.service = service;
    }

    @GetMapping
    public List<PaymentGatewayConfigDto> index() {
        return service.list();
    }

    @PutMapping("/{gateway}")
    public PaymentGatewayConfigDto update(@PathVariable PaymentGatewayType gateway, @Valid @RequestBody PaymentGatewayConfigCommand command) {
        return service.upsert(gateway, command);
    }
}
