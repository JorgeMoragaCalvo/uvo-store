package org.uvo.uvostore.service.catalog;

import org.uvo.uvostore.entity.catalog.Category;

final class CategoryDtoMapper {

    private CategoryDtoMapper() {
    }

    static CategoryDto toDto(Category category, FileStorageService fileStorageService) {
        CategoryRefDto parent = category.getParent() == null ? null
                : new CategoryRefDto(category.getParent().getId(), category.getParent().getName(), category.getParent().getSlug());

        return new CategoryDto(
                category.getId(),
                category.getName(),
                category.getSlug(),
                category.getDescription(),
                fileStorageService.publicUrl(category.getImage()),
                category.getParent() == null ? null : category.getParent().getId(),
                category.isActive(),
                category.getSortOrder(),
                parent,
                category.getChildren().stream()
                        .map(child -> new CategoryRefDto(child.getId(), child.getName(), child.getSlug()))
                        .toList(),
                category.getProductsCount()
        );
    }
}
