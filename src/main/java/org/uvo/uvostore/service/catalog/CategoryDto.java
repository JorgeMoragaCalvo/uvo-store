package org.uvo.uvostore.service.catalog;

import java.util.List;

public record CategoryDto(
        Long id,
        String name,
        String slug,
        String description,
        String image,
        Long parentId,
        boolean active,
        int sortOrder,
        CategoryRefDto parent,
        List<CategoryRefDto> children,
        Integer productsCount
) {
}
