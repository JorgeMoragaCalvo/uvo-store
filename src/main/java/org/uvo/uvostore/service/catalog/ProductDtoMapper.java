package org.uvo.uvostore.service.catalog;

import org.uvo.uvostore.entity.catalog.Product;
import org.uvo.uvostore.entity.catalog.ProductImage;
import org.uvo.uvostore.entity.catalog.ProductVariation;
import org.uvo.uvostore.entity.catalog.ProductVariationAttribute;
import org.uvo.uvostore.entity.catalog.enums.ProductType;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

// Ports Product's/ProductVariation's/ProductImage's Eloquent accessors
// (getFormattedPriceAttribute/getFeaturedImageAttribute/getInStockAttribute,
// ProductImage::url/thumbnail_url, ProductVariation::attributes_array/attribute_ids_array) —
// see app/Models/Product.php, ProductImage.php, ProductVariation.php.
final class ProductDtoMapper {

    private ProductDtoMapper() {
    }

    static ProductDto toDto(Product product, FileStorageService fileStorageService) {
        List<ProductImageDto> images = product.getProductImages().stream()
                .sorted(Comparator.comparingInt(ProductImage::getSortOrder))
                .map(image -> toImageDto(image, fileStorageService))
                .toList();

        List<ProductVariation> activeVariations = product.getVariations().stream()
                .filter(ProductVariation::isActive)
                .toList();

        CategoryRefDto category = product.getCategory() == null ? null
                : new CategoryRefDto(product.getCategory().getId(), product.getCategory().getName(), product.getCategory().getSlug());

        return new ProductDto(
                product.getId(),
                product.getName(),
                product.getSlug(),
                product.getShortDescription(),
                product.getDescription(),
                product.getProductType().name().toLowerCase(),
                product.getSku(),
                product.getPrice(),
                formattedPrice(product, activeVariations),
                product.getStock(),
                inStock(product, activeVariations),
                product.isManageStock(),
                featuredImageUrl(images),
                images,
                product.isActive(),
                product.isFeatured(),
                product.getMetaTitle(),
                product.getMetaDescription(),
                category,
                product.getVariations().stream().map(variation -> toVariationDto(variation, fileStorageService)).toList(),
                product.getVariations().size(),
                product.getCreatedAt(),
                product.getUpdatedAt()
        );
    }

    static ProductImageDto toImageDto(ProductImage image, FileStorageService fileStorageService) {
        String url = fileStorageService.publicUrl(image.getImagePath());
        return new ProductImageDto(image.getId(), url, url, image.getAltText(), image.isFeatured());
    }

    static ProductVariationDto toVariationDto(ProductVariation variation, FileStorageService fileStorageService) {
        Map<String, String> attributes = new HashMap<>();
        Map<String, Long> attributeIds = new HashMap<>();
        for (ProductVariationAttribute link : variation.getAttributeAssignments()) {
            attributes.put(link.getAttribute().getName(), link.getAttributeValue().getValue());
            attributeIds.put(link.getAttribute().getSlug(), link.getAttributeValue().getId());
        }

        String image = fileStorageService.publicUrl(variation.getImage());

        return new ProductVariationDto(
                variation.getId(),
                variation.getProduct().getId(),
                variation.getSku(),
                variation.getPrice(),
                variation.getCompareAtPrice(),
                formatClp(variation.getPrice()),
                variation.getStock(),
                variation.isActive() && variation.getStock() > 0,
                variation.getWeight(),
                image,
                variation.isActive(),
                attributes,
                attributeIds,
                variation.getCreatedAt()
        );
    }

    private static String featuredImageUrl(List<ProductImageDto> images) {
        return images.stream().filter(ProductImageDto::isFeatured).findFirst()
                .or(() -> images.stream().findFirst())
                .map(ProductImageDto::url)
                .orElse(null);
    }

    private static boolean inStock(Product product, List<ProductVariation> activeVariations) {
        if (product.getProductType() == ProductType.SIMPLE) {
            return product.getStock() > 0;
        }
        return activeVariations.stream().anyMatch(v -> v.getStock() > 0);
    }

    private static String formattedPrice(Product product, List<ProductVariation> activeVariations) {
        if (product.getProductType() == ProductType.VARIABLE) {
            if (activeVariations.isEmpty()) {
                return "No disponible";
            }
            BigDecimal min = activeVariations.stream().map(ProductVariation::getPrice).min(BigDecimal::compareTo).orElse(BigDecimal.ZERO);
            BigDecimal max = activeVariations.stream().map(ProductVariation::getPrice).max(BigDecimal::compareTo).orElse(BigDecimal.ZERO);
            if (min.compareTo(max) == 0) {
                return formatClp(min);
            }
            return formatClp(min) + " - " + formatClp(max);
        }
        return product.getPrice() == null ? "No disponible" : formatClp(product.getPrice());
    }

    // Ports number_format($price, 0, ',', '.') — thousands separated with '.', no decimals.
    static String formatClp(BigDecimal amount) {
        if (amount == null) {
            return "No disponible";
        }
        long rounded = amount.setScale(0, RoundingMode.HALF_UP).longValueExact();
        String digits = Long.toString(Math.abs(rounded));
        StringBuilder grouped = new StringBuilder();
        int count = 0;
        for (int i = digits.length() - 1; i >= 0; i--) {
            grouped.append(digits.charAt(i));
            count++;
            if (count % 3 == 0 && i != 0) {
                grouped.append('.');
            }
        }
        return "$" + (rounded < 0 ? "-" : "") + grouped.reverse();
    }
}
