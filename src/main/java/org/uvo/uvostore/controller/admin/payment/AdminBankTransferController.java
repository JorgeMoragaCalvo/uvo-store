package org.uvo.uvostore.controller.admin.payment;

import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.uvo.uvostore.service.payment.transfer.BankTransferCommand;
import org.uvo.uvostore.service.payment.transfer.BankTransferDto;
import org.uvo.uvostore.service.payment.transfer.BankTransferService;

/** IMP-07. El pago manual de la tienda: transferencia bancaria con sus datos y su plazo. */
@io.swagger.v3.oas.annotations.tags.Tag(name = "Pasarelas de pago (admin)", description = "Configuración de Webpay/MercadoPago por tienda, JWT bearer con rol ADMIN")
@io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/api/admin/payment-methods/bank-transfer")
public class AdminBankTransferController {

    private final BankTransferService service;

    public AdminBankTransferController(BankTransferService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('payments.view')")
    public BankTransferDto show() {
        return service.get();
    }

    @PutMapping
    @PreAuthorize("hasAuthority('payments.manage')")
    public BankTransferDto update(@Valid @RequestBody BankTransferCommand command) {
        return service.update(command);
    }
}
