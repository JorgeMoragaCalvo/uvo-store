package org.uvo.uvostore.controller.admin.catalog;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.uvo.uvostore.service.catalog.AttributeCommand;
import org.uvo.uvostore.service.catalog.AttributeDto;
import org.uvo.uvostore.service.catalog.AttributeService;
import org.uvo.uvostore.service.catalog.AttributeValueCommand;

import java.util.List;

@io.swagger.v3.oas.annotations.tags.Tag(name = "Atributos (admin)", description = "CRUD de atributos y sus valores, JWT bearer con rol ADMIN")
@io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/api/admin/attributes")
public class AdminAttributeController {

    private final AttributeService attributeService;

    public AdminAttributeController(AttributeService attributeService) {
        this.attributeService = attributeService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('products.view')")
    public List<AttributeDto> index() {
        return attributeService.listAll();
    }

    @PostMapping
    @PreAuthorize("hasAuthority('products.manage')")
    public AttributeDto create(@RequestBody AttributeRequest request) {
        return attributeService.createAttribute(new AttributeCommand(request.name(), request.slug(), request.type()));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('products.manage')")
    public AttributeDto update(@PathVariable Long id, @RequestBody AttributeRequest request) {
        return attributeService.updateAttribute(id, new AttributeCommand(request.name(), request.slug(), request.type()));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('products.manage')")
    public void delete(@PathVariable Long id) {
        attributeService.deleteAttribute(id);
    }

    @PostMapping("/{attributeId}/values")
    @PreAuthorize("hasAuthority('products.manage')")
    public AttributeDto createValue(@PathVariable Long attributeId, @RequestBody AttributeValueRequest request) {
        return attributeService.createValue(new AttributeValueCommand(
                attributeId, request.value(), request.slug(), request.colorHex(), request.sortOrder()));
    }

    @PutMapping("/values/{valueId}")
    @PreAuthorize("hasAuthority('products.manage')")
    public AttributeDto updateValue(@PathVariable Long valueId, @RequestBody AttributeValueRequest request) {
        return attributeService.updateValue(valueId, new AttributeValueCommand(
                null, request.value(), request.slug(), request.colorHex(), request.sortOrder()));
    }

    @DeleteMapping("/values/{valueId}")
    @PreAuthorize("hasAuthority('products.manage')")
    public AttributeDto deleteValue(@PathVariable Long valueId) {
        return attributeService.deleteValue(valueId);
    }
}
