package org.uvo.uvostore.controller.admin.catalog;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.uvo.uvostore.entity.catalog.Product;
import org.uvo.uvostore.entity.catalog.enums.ProductType;
import org.uvo.uvostore.service.catalog.AdminProductSearchCriteria;
import org.uvo.uvostore.service.catalog.AdminProductStatsDto;
import org.uvo.uvostore.service.catalog.ProductCreateCommand;
import org.uvo.uvostore.service.catalog.ProductDto;
import org.uvo.uvostore.service.catalog.ProductQueryService;
import org.uvo.uvostore.service.catalog.ProductService;

import java.util.List;

@RestController
@RequestMapping("/api/admin/products")
public class AdminProductController {

    private final ProductService productService;
    private final ProductQueryService productQueryService;

    public AdminProductController(ProductService productService, ProductQueryService productQueryService) {
        this.productService = productService;
        this.productQueryService = productQueryService;
    }

    @GetMapping
    public Page<ProductDto> index(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) Long categoryId,
            @RequestParam(required = false) Boolean active,
            @RequestParam(required = false) Boolean featured,
            @RequestParam(required = false) String stockStatus,
            @RequestParam(required = false) String priceRange,
            @RequestParam(defaultValue = "createdAt") String sortField,
            @RequestParam(defaultValue = "desc") String sortDirection,
            @RequestParam(defaultValue = "15") int perPage,
            @RequestParam(defaultValue = "1") int page
    ) {
        ProductType productType = type == null || type.isBlank() ? null : ProductType.valueOf(type.toUpperCase());
        AdminProductSearchCriteria criteria = new AdminProductSearchCriteria(
                search, productType, categoryId, active, featured, stockStatus, priceRange);
        Sort.Direction direction = "asc".equalsIgnoreCase(sortDirection) ? Sort.Direction.ASC : Sort.Direction.DESC;
        return productQueryService.searchAdmin(criteria, PageRequest.of(Math.max(page - 1, 0), Math.min(perPage, 100), Sort.by(direction, sortField)));
    }

    @GetMapping("/stats")
    public AdminProductStatsDto stats() {
        return productQueryService.getStats();
    }

    @GetMapping("/{id}")
    public ProductDto show(@PathVariable Long id) {
        return productQueryService.getById(id);
    }

    @PostMapping(consumes = "multipart/form-data")
    public ProductDto create(@ModelAttribute ProductRequest request) {
        ProductCreateCommand command = toCommand(request);
        Product product = "variable".equalsIgnoreCase(request.productType())
                ? productService.createVariableProduct(command, List.of())
                : productService.createSimpleProduct(command);
        return productQueryService.getById(product.getId());
    }

    @PutMapping(value = "/{id}", consumes = "multipart/form-data")
    public ProductDto update(@PathVariable Long id, @ModelAttribute ProductRequest request) {
        Product product = productService.updateProduct(id, toCommand(request));
        return productQueryService.getById(product.getId());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        productService.deleteProduct(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/toggle-active")
    public ProductDto toggleActive(@PathVariable Long id) {
        Product product = productService.toggleActive(id);
        return productQueryService.getById(product.getId());
    }

    @PostMapping("/{id}/toggle-featured")
    public ProductDto toggleFeatured(@PathVariable Long id) {
        Product product = productService.toggleFeatured(id);
        return productQueryService.getById(product.getId());
    }

    private ProductCreateCommand toCommand(ProductRequest r) {
        return new ProductCreateCommand(
                r.name(), r.shortDescription(), r.description(), r.categoryId(), r.posProductId(),
                r.active(), r.isFeatured(), r.metaTitle(), r.metaDescription(), r.isNew(), r.newUntil(),
                r.sortOrder(), r.isOnSale(), r.salePrice(), r.discountPercentage(), r.saleStartsAt(), r.saleEndsAt(),
                r.sku(), r.price(), r.stock(), r.manageStock(), r.featuredImage(), r.images()
        );
    }
}
