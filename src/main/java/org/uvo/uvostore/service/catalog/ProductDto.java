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
        // F11: `price` es el precio VIGENTE (con la oferta aplicada si la hay) y `compareAtPrice` el
        // habitual cuando hay algo que tachar, o null. Es el mismo convenio que ya usaba
        // ProductVariationDto, para que el storefront pinte las dos mitades del catálogo igual.
        BigDecimal price,
        BigDecimal compareAtPrice,
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
