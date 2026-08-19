package org.uvo.uvostore.service.catalog;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;

public interface ProductQueryService {
    Page<ProductDto> search(ProductSearchCriteria criteria, Pageable pageable);
    List<ProductDto> featured(int limit);
    ProductDto getBySlug(String slug);
    List<ProductDto> related(String slug, int limit);

    // Admin reads — no active-only filter, unlike the storefront methods above.
    ProductDto getById(Long id);
    Page<ProductDto> searchAdmin(AdminProductSearchCriteria criteria, Pageable pageable);
    AdminProductStatsDto getStats();
}
