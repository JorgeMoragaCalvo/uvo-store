package org.uvo.uvostore.controller.admin.catalog;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
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
import org.uvo.uvostore.service.catalog.ProductImageService;
import org.uvo.uvostore.service.catalog.ProductQueryService;
import org.uvo.uvostore.service.catalog.ProductService;

import java.util.List;

@io.swagger.v3.oas.annotations.tags.Tag(name = "Productos (admin)", description = "CRUD de productos, JWT bearer con rol ADMIN")
@io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/api/admin/products")
public class AdminProductController {

    // M8: sortField came straight from the query string into Sort.by(...), so any name that isn't a
    // real property blew up as a 500 (PropertyReferenceException) and every column of the entity was
    // orderable, including columns nobody sorts by. Same allowlist-with-silent-fallback that
    // ProductController:25,52 already used on the public side.
    private static final java.util.Set<String> ALLOWED_SORTS = java.util.Set.of("createdAt", "name", "price", "stock", "sku", "salesCount", "sortOrder");

    private final ProductService productService;
    private final ProductQueryService productQueryService;
    private final ProductImageService productImageService;

    public AdminProductController(ProductService productService, ProductQueryService productQueryService,
                                  ProductImageService productImageService) {
        this.productService = productService;
        this.productQueryService = productQueryService;
        this.productImageService = productImageService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('products.view')")
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
        String safeSort = ALLOWED_SORTS.contains(sortField) ? sortField : "createdAt";
        Sort.Direction direction = "asc".equalsIgnoreCase(sortDirection) ? Sort.Direction.ASC : Sort.Direction.DESC;
        return productQueryService.searchAdmin(criteria, PageRequest.of(Math.max(page - 1, 0), Math.min(perPage, 100), Sort.by(direction, safeSort)));
    }

    @GetMapping("/stats")
    @PreAuthorize("hasAuthority('products.view')")
    public AdminProductStatsDto stats() {
        return productQueryService.getStats();
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('products.view')")
    public ProductDto show(@PathVariable Long id) {
        return productQueryService.getById(id);
    }

    @PostMapping(consumes = "multipart/form-data")
    @PreAuthorize("hasAuthority('products.manage')")
    public ProductDto create(@ModelAttribute ProductRequest request) {
        ProductCreateCommand command = toCommand(request);
        Product product = "variable".equalsIgnoreCase(request.productType())
                ? productService.createVariableProduct(command, List.of())
                : productService.createSimpleProduct(command);
        return productQueryService.getById(product.getId());
    }

    @PutMapping(value = "/{id}", consumes = "multipart/form-data")
    @PreAuthorize("hasAuthority('products.manage')")
    public ProductDto update(@PathVariable Long id, @ModelAttribute ProductRequest request) {
        Product product = productService.updateProduct(id, toCommand(request));
        return productQueryService.getById(product.getId());
    }

    // Same shape as AdminCategoryController's DELETE /{id}/image, but per-image because products
    // have a gallery. Returns the refreshed product so the form can re-render from the response
    // instead of firing a second request.
    @DeleteMapping("/{id}/images/{imageId}")
    @PreAuthorize("hasAuthority('products.manage')")
    public ProductDto removeImage(@PathVariable Long id, @PathVariable Long imageId) {
        productImageService.removeImage(id, imageId);
        return productQueryService.getById(id);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('products.manage')")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        productService.deleteProduct(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/toggle-active")
    @PreAuthorize("hasAuthority('products.manage')")
    public ProductDto toggleActive(@PathVariable Long id) {
        Product product = productService.toggleActive(id);
        return productQueryService.getById(product.getId());
    }

    @PostMapping("/{id}/toggle-featured")
    @PreAuthorize("hasAuthority('products.manage')")
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
