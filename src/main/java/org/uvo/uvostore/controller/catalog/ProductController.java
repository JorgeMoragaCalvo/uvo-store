package org.uvo.uvostore.controller.catalog;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.uvo.uvostore.entity.catalog.enums.ProductType;
import org.uvo.uvostore.service.catalog.ProductDto;
import org.uvo.uvostore.service.catalog.ProductQueryService;
import org.uvo.uvostore.service.catalog.ProductSearchCriteria;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

@RestController
@RequestMapping("/api/v1/products")
public class ProductController {

    private static final Set<String> ALLOWED_SORTS = Set.of("name", "price", "createdAt", "stock");

    private final ProductQueryService productQueryService;

    public ProductController(ProductQueryService productQueryService) {
        this.productQueryService = productQueryService;
    }

    @GetMapping
    public Page<ProductDto> index(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String type,
            @RequestParam(required = false, defaultValue = "false") boolean featured,
            @RequestParam(name = "in_stock", required = false, defaultValue = "false") boolean inStock,
            @RequestParam(name = "is_new", required = false, defaultValue = "false") boolean isNew,
            @RequestParam(name = "on_sale", required = false, defaultValue = "false") boolean onSale,
            @RequestParam(name = "min_price", required = false) BigDecimal minPrice,
            @RequestParam(name = "max_price", required = false) BigDecimal maxPrice,
            @RequestParam(name = "sort_by", required = false, defaultValue = "createdAt") String sortBy,
            @RequestParam(name = "sort_order", required = false, defaultValue = "desc") String sortOrder,
            @RequestParam(name = "per_page", required = false, defaultValue = "12") int perPage,
            @RequestParam(required = false, defaultValue = "1") int page
    ) {
        ProductType productType = type == null ? null : ProductType.valueOf(type.toUpperCase());
        ProductSearchCriteria criteria = new ProductSearchCriteria(search, category, productType, featured, inStock, isNew, onSale, minPrice, maxPrice);

        String sortField = ALLOWED_SORTS.contains(sortBy) ? sortBy : "createdAt";
        Sort.Direction direction = "asc".equalsIgnoreCase(sortOrder) ? Sort.Direction.ASC : Sort.Direction.DESC;
        int safePerPage = Math.min(perPage, 100);
        int safePage = Math.max(page - 1, 0);

        return productQueryService.search(criteria, PageRequest.of(safePage, safePerPage, Sort.by(direction, sortField)));
    }

    @GetMapping("/featured")
    public List<ProductDto> featured() {
        return productQueryService.featured(8);
    }

    @GetMapping("/{slug}")
    public ProductDto show(@PathVariable String slug) {
        return productQueryService.getBySlug(slug);
    }

    @GetMapping("/{slug}/related")
    public List<ProductDto> related(@PathVariable String slug) {
        return productQueryService.related(slug, 4);
    }
}
