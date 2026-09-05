package org.uvo.uvostore.controller.admin.shipping;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.uvo.uvostore.service.shipping.AdminShippingMethodService;
import org.uvo.uvostore.service.shipping.ShippingMethodCommand;
import org.uvo.uvostore.service.shipping.ShippingMethodDto;

import java.util.List;
import java.util.Map;

@io.swagger.v3.oas.annotations.tags.Tag(name = "Métodos de envío (admin)", description = "CRUD de métodos de envío y sus credenciales de transportista, JWT bearer con rol ADMIN")
@io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/api/admin/shipping/methods")
public class AdminShippingMethodController {

    private final AdminShippingMethodService adminShippingMethodService;

    public AdminShippingMethodController(AdminShippingMethodService adminShippingMethodService) {
        this.adminShippingMethodService = adminShippingMethodService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('shipping.view')")
    public List<ShippingMethodDto> index() {
        return adminShippingMethodService.list();
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('shipping.view')")
    public ShippingMethodDto show(@PathVariable Long id) {
        return adminShippingMethodService.getById(id);
    }

    @PostMapping
    @PreAuthorize("hasAuthority('shipping.manage')")
    public ShippingMethodDto create(@Valid @RequestBody ShippingMethodRequest request) {
        return adminShippingMethodService.create(toCommand(request));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('shipping.manage')")
    public ShippingMethodDto update(@PathVariable Long id, @Valid @RequestBody ShippingMethodRequest request) {
        return adminShippingMethodService.update(id, toCommand(request));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('shipping.manage')")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        adminShippingMethodService.delete(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/toggle-status")
    @PreAuthorize("hasAuthority('shipping.manage')")
    public ShippingMethodDto toggleStatus(@PathVariable Long id) {
        return adminShippingMethodService.toggleStatus(id);
    }

    @PutMapping("/{id}/credentials")
    @PreAuthorize("hasAuthority('shipping.manage')")
    public ShippingMethodDto updateCredentials(@PathVariable Long id, @RequestBody Map<String, String> credentials) {
        return adminShippingMethodService.updateCredentials(id, credentials);
    }

    private ShippingMethodCommand toCommand(ShippingMethodRequest r) {
        return new ShippingMethodCommand(r.name(), r.code(), r.description(), r.type(), r.hasApiIntegration(), r.carrier(),
                r.minDeliveryDays(), r.maxDeliveryDays(), r.active(), r.sortOrder());
    }
}
