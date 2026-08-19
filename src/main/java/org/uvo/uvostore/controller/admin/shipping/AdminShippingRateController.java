package org.uvo.uvostore.controller.admin.shipping;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.uvo.uvostore.service.shipping.AdminShippingRateService;
import org.uvo.uvostore.service.shipping.ShippingRateCommand;
import org.uvo.uvostore.service.shipping.ShippingRateDto;

import java.util.List;

@RestController
@RequestMapping("/api/admin/shipping/rates")
public class AdminShippingRateController {

    private final AdminShippingRateService adminShippingRateService;

    public AdminShippingRateController(AdminShippingRateService adminShippingRateService) {
        this.adminShippingRateService = adminShippingRateService;
    }

    @GetMapping
    public List<ShippingRateDto> index(@RequestParam(required = false) Long methodId, @RequestParam(required = false) Long zoneId) {
        return adminShippingRateService.list(methodId, zoneId);
    }

    @GetMapping("/{id}")
    public ShippingRateDto show(@PathVariable Long id) {
        return adminShippingRateService.getById(id);
    }

    @PostMapping
    public ShippingRateDto create(@Valid @RequestBody ShippingRateRequest request) {
        return adminShippingRateService.create(toCommand(request));
    }

    @PutMapping("/{id}")
    public ShippingRateDto update(@PathVariable Long id, @Valid @RequestBody ShippingRateRequest request) {
        return adminShippingRateService.update(id, toCommand(request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        adminShippingRateService.delete(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/toggle-status")
    public ShippingRateDto toggleStatus(@PathVariable Long id) {
        return adminShippingRateService.toggleStatus(id);
    }

    private ShippingRateCommand toCommand(ShippingRateRequest r) {
        return new ShippingRateCommand(
                r.methodId(), r.zoneId(), r.name(), r.rateType(), r.flatRate(), r.weightRatePerKg(), r.baseWeightRate(),
                r.minOrderAmount(), r.maxOrderAmount(), r.minWeight(), r.maxWeight(), r.freeShippingThreshold(),
                r.active(), r.sortOrder()
        );
    }
}
