package org.uvo.uvostore.service.catalog;

public record AttributeValueCommand(Long attributeId, String value, String slug, String colorHex, int sortOrder) {
}
