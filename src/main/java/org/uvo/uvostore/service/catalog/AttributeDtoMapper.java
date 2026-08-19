package org.uvo.uvostore.service.catalog;

import org.uvo.uvostore.entity.catalog.Attribute;
import org.uvo.uvostore.entity.catalog.AttributeValue;

import java.util.Comparator;

final class AttributeDtoMapper {

    private AttributeDtoMapper() {
    }

    static AttributeDto toDto(Attribute attribute) {
        return new AttributeDto(
                attribute.getId(),
                attribute.getName(),
                attribute.getSlug(),
                attribute.getType().name().toLowerCase(),
                attribute.getValues().stream()
                        .sorted(Comparator.comparingInt(AttributeValue::getSortOrder).thenComparing(AttributeValue::getValue))
                        .map(v -> new AttributeValueDto(v.getId(), attribute.getId(), v.getValue(), v.getSlug(), v.getColorHex(), v.getSortOrder()))
                        .toList()
        );
    }
}
