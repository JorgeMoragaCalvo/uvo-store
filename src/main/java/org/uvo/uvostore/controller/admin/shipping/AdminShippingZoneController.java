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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.uvo.uvostore.service.shipping.AdminShippingZoneService;
import org.uvo.uvostore.service.shipping.ShippingZoneCommand;
import org.uvo.uvostore.service.shipping.ShippingZoneDto;

import java.util.List;

@io.swagger.v3.oas.annotations.tags.Tag(name = "Zonas de envío (admin)", description = "CRUD de zonas de cobertura por región/comuna, JWT bearer con rol ADMIN")
@io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/api/admin/shipping/zones")
public class AdminShippingZoneController {

    private final AdminShippingZoneService adminShippingZoneService;

    public AdminShippingZoneController(AdminShippingZoneService adminShippingZoneService) {
        this.adminShippingZoneService = adminShippingZoneService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('shipping.view')")
    public List<ShippingZoneDto> index(@RequestParam(required = false) String search) {
        return adminShippingZoneService.list(search);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('shipping.view')")
    public ShippingZoneDto show(@PathVariable Long id) {
        return adminShippingZoneService.getById(id);
    }

    @PostMapping
    @PreAuthorize("hasAuthority('shipping.manage')")
    public ShippingZoneDto create(@Valid @RequestBody ShippingZoneRequest request) {
        return adminShippingZoneService.create(toCommand(request));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('shipping.manage')")
    public ShippingZoneDto update(@PathVariable Long id, @Valid @RequestBody ShippingZoneRequest request) {
        return adminShippingZoneService.update(id, toCommand(request));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('shipping.manage')")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        adminShippingZoneService.delete(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/toggle-status")
    @PreAuthorize("hasAuthority('shipping.manage')")
    public ShippingZoneDto toggleStatus(@PathVariable Long id) {
        return adminShippingZoneService.toggleStatus(id);
    }

    private ShippingZoneCommand toCommand(ShippingZoneRequest r) {
        return new ShippingZoneCommand(r.name(), r.description(), r.regions(), r.communes(), r.active(), r.sortOrder());
    }
}
