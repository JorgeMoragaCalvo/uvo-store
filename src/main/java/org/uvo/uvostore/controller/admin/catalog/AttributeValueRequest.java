package org.uvo.uvostore.controller.admin.catalog;

public record AttributeValueRequest(String value, String slug, String colorHex, int sortOrder) {
}
