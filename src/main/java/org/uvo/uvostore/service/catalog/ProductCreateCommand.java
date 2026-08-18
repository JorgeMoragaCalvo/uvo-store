package org.uvo.uvostore.service.catalog;

import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

// Mirrors every field ProductCreate.php's save() actually persists
// (app/Livewire/Admin/Products/ProductCreate.php:143-177), split from the Livewire component's
// live-bound properties into one command record. sku/price/stock/manageStock only apply to
// simple products (Laravel only validates/assigns them when product_type === 'simple') — pass
// null/default for variable products, ProductServiceImpl.createVariableProduct ignores them.
public record ProductCreateCommand(
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
