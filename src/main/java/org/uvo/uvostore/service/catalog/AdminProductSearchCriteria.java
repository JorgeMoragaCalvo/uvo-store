package org.uvo.uvostore.service.catalog;

import org.uvo.uvostore.entity.catalog.enums.ProductType;

public record AdminProductSearchCriteria(
        String search,
        ProductType type,
        Long categoryId,
        Boolean active,
        Boolean featured,
        String stockStatus, // "out" | "low" | null — ProductIndexPro::getQuery()
        String priceRange   // "0-10000" | "10000-50000" | "50000+" | null
) {
}
