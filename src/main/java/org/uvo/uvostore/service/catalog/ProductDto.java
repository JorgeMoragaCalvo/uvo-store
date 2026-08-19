package org.uvo.uvostore.service.catalog;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record ProductDto(
        Long id,
        String name,
        String slug,
        String shortDescription,
        String description,
        String productType,
        String sku,
        BigDecimal price,
        String formattedPrice,
        int stock,
        boolean inStock,
        boolean manageStock,
        String featuredImage,
        List<ProductImageDto> images,
        boolean active,
        boolean featured,
        String metaTitle,
        String metaDescription,
        CategoryRefDto category,
        List<ProductVariationDto> variations,
        Integer variationsCount,
        Instant createdAt,
        Instant updatedAt
) {
}
