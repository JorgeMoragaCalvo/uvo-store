package org.uvo.uvostore.service.catalog;

import java.util.List;

public record AttributeDto(Long id, String name, String slug, String type, List<AttributeValueDto> values) {
}
