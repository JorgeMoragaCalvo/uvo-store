package org.uvo.uvostore.service.catalog;

import java.util.List;

public interface CategoryQueryService {
    List<CategoryDto> rootCategories();
    CategoryDto getBySlug(String slug);

    // Admin reads — every category regardless of active flag.
    List<CategoryDto> listAll();
    CategoryDto getById(Long id);
}
