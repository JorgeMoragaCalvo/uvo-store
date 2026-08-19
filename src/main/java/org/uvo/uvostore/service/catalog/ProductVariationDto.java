package org.uvo.uvostore.service.catalog;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

public record ProductVariationDto(
        Long id,
        Long productId,
        String sku,
        BigDecimal price,
        BigDecimal compareAtPrice,
        String formattedPrice,
        int stock,
        boolean inStock,
        BigDecimal weight,
        String image,
        boolean active,
        Map<String, String> attributes,
        Map<String, Long> attributeIds,
        Instant createdAt
) {
}
