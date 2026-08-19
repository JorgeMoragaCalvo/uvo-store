package org.uvo.uvostore.controller.catalog;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.uvo.uvostore.service.catalog.AttributeDto;
import org.uvo.uvostore.service.catalog.AttributeQueryService;

import java.util.List;

@RestController
@RequestMapping("/api/v1/attributes")
public class AttributeController {

    private final AttributeQueryService attributeQueryService;

    public AttributeController(AttributeQueryService attributeQueryService) {
        this.attributeQueryService = attributeQueryService;
    }

    @GetMapping
    public List<AttributeDto> index() {
        return attributeQueryService.listAll();
    }
}
