package org.uvo.uvostore.controller.admin.catalog;

import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record ProductRequest(
        String productType, // "simple" | "variable"
        String name,
        String shortDescription,
        String description,
        Long categoryId,
        String posProductId,
        boolean active,
        boolean isFeatured,
        String metaTitle,
        String metaDescription,
        boolean isNew,
        LocalDate newUntil,
        int sortOrder,
        boolean isOnSale,
        BigDecimal salePrice,
        Integer discountPercentage,
        Instant saleStartsAt,
        Instant saleEndsAt,
        String sku,
        BigDecimal price,
        Integer stock,
        boolean manageStock,
        MultipartFile featuredImage,
        List<MultipartFile> images
) {
}
