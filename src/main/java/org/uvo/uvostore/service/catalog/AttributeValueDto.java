package org.uvo.uvostore.service.catalog;

public record AttributeValueDto(Long id, Long attributeId, String value, String slug, String colorHex, int sortOrder) {
}
