package org.uvo.uvostore.service.catalog;

import org.uvo.uvostore.entity.catalog.enums.ProductType;

import java.math.BigDecimal;

public record ProductSearchCriteria(
        String search,
        String categorySlug,
        ProductType type,
        boolean featuredOnly,
        boolean inStockOnly,
        BigDecimal minPrice,
        BigDecimal maxPrice
) {
}
