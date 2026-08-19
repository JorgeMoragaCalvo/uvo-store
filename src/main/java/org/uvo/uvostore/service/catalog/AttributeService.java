package org.uvo.uvostore.service.catalog;

import java.util.List;

// Ports Admin\Attributes\AttributeIndex — Attribute + AttributeValue CRUD for the admin panel.
// Distinct from AttributeQueryService (Fase 2), which is the read-only storefront listing.
public interface AttributeService {
    List<AttributeDto> listAll();
    AttributeDto createAttribute(AttributeCommand command);
    AttributeDto updateAttribute(Long id, AttributeCommand command);
    void deleteAttribute(Long id);

    AttributeDto createValue(AttributeValueCommand command);
    AttributeDto updateValue(Long valueId, AttributeValueCommand command);
    AttributeDto deleteValue(Long valueId);
}
