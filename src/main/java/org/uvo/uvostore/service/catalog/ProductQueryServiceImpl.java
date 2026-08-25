package org.uvo.uvostore.service.catalog;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.uvo.uvostore.entity.catalog.Category;
import org.uvo.uvostore.entity.catalog.Product;
import org.uvo.uvostore.entity.catalog.enums.ProductType;
import org.uvo.uvostore.repository.ProductRepository;
import org.uvo.uvostore.security.TenantContext;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;

@Service
public class ProductQueryServiceImpl implements ProductQueryService {

    private final ProductRepository productRepository;
    private final FileStorageService fileStorageService;

    public ProductQueryServiceImpl(ProductRepository productRepository, FileStorageService fileStorageService) {
        this.productRepository = productRepository;
        this.fileStorageService = fileStorageService;
    }

    @Override
    @Transactional(readOnly = true)
    public Page<ProductDto> search(ProductSearchCriteria criteria, Pageable pageable) {
        Specification<Product> spec = buildSpecification(criteria);
        return productRepository.findAll(spec, pageable).map(product -> ProductDtoMapper.toDto(product, fileStorageService));
    }

    @Override
    @Transactional(readOnly = true)
    public List<ProductDto> featured(int limit) {
        return productRepository.findByStoreIdAndIsFeaturedTrueAndActiveTrue(TenantContext.requireStoreId()).stream()
                .limit(limit)
                .map(product -> ProductDtoMapper.toDto(product, fileStorageService))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public ProductDto getBySlug(String slug) {
        Product product = productRepository.findByStoreIdAndSlug(TenantContext.requireStoreId(), slug)
                .filter(Product::isActive)
                .orElseThrow(() -> new NoSuchElementException("Product " + slug + " not found"));
        return ProductDtoMapper.toDto(product, fileStorageService);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ProductDto> related(String slug, int limit) {
        Long storeId = TenantContext.requireStoreId();
        Product product = productRepository.findByStoreIdAndSlug(storeId, slug)
                .orElseThrow(() -> new NoSuchElementException("Product " + slug + " not found"));
        if (product.getCategory() == null) {
            return List.of();
        }

        return productRepository.findByStoreIdAndCategoryId(storeId, product.getCategory().getId()).stream()
                .filter(Product::isActive)
                .filter(p -> !p.getId().equals(product.getId()))
                .filter(this::isInStock)
                .limit(limit)
                .map(p -> ProductDtoMapper.toDto(p, fileStorageService))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public ProductDto getById(Long id) {
        Product product = productRepository.findById(id)
                .filter(p -> p.getStore().getId().equals(TenantContext.requireStoreId()))
                .orElseThrow(() -> new NoSuchElementException("Product " + id + " not found"));
        return ProductDtoMapper.toDto(product, fileStorageService);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<ProductDto> searchAdmin(AdminProductSearchCriteria criteria, Pageable pageable) {
        return productRepository.findAll(buildAdminSpecification(criteria), pageable)
                .map(product -> ProductDtoMapper.toDto(product, fileStorageService));
    }

    @Override
    @Transactional(readOnly = true)
    public AdminProductStatsDto getStats() {
        Long storeId = TenantContext.requireStoreId();
        long total = productRepository.count((root, query, cb) -> cb.equal(root.get("store").get("id"), storeId));
        long active = productRepository.count((root, query, cb) -> cb.and(
                cb.equal(root.get("store").get("id"), storeId), cb.isTrue(root.get("active"))));
        long outOfStock = productRepository.count((root, query, cb) -> cb.and(
                cb.equal(root.get("store").get("id"), storeId),
                cb.equal(root.get("productType"), ProductType.SIMPLE), cb.equal(root.get("stock"), 0)));
        long lowStock = productRepository.count((root, query, cb) -> cb.and(
                cb.equal(root.get("store").get("id"), storeId),
                cb.equal(root.get("productType"), ProductType.SIMPLE),
                cb.greaterThan(root.get("stock"), 0), cb.lessThanOrEqualTo(root.get("stock"), 5)));
        return new AdminProductStatsDto(total, active, outOfStock, lowStock);
    }

    private Specification<Product> buildAdminSpecification(AdminProductSearchCriteria criteria) {
        List<Specification<Product>> specs = new ArrayList<>();
        Long storeId = TenantContext.requireStoreId();
        specs.add((root, query, cb) -> cb.equal(root.get("store").get("id"), storeId));

        if (criteria.search() != null && !criteria.search().isBlank()) {
            String term = "%" + criteria.search().toLowerCase() + "%";
            specs.add((root, query, cb) -> cb.or(
                    cb.like(cb.lower(root.get("name")), term),
                    cb.like(cb.lower(root.get("sku")), term),
                    cb.like(cb.lower(root.get("shortDescription")), term)
            ));
        }
        if (criteria.type() != null) {
            specs.add((root, query, cb) -> cb.equal(root.get("productType"), criteria.type()));
        }
        if (criteria.categoryId() != null) {
            specs.add((root, query, cb) -> cb.equal(root.get("category").get("id"), criteria.categoryId()));
        }
        if (criteria.active() != null) {
            specs.add((root, query, cb) -> cb.equal(root.get("active"), criteria.active()));
        }
        if (criteria.featured() != null) {
            specs.add((root, query, cb) -> cb.equal(root.get("isFeatured"), criteria.featured()));
        }
        if ("out".equals(criteria.stockStatus())) {
            specs.add((root, query, cb) -> cb.and(
                    cb.equal(root.get("productType"), ProductType.SIMPLE), cb.equal(root.get("stock"), 0)));
        } else if ("low".equals(criteria.stockStatus())) {
            specs.add((root, query, cb) -> cb.and(
                    cb.equal(root.get("productType"), ProductType.SIMPLE),
                    cb.greaterThan(root.get("stock"), 0), cb.lessThanOrEqualTo(root.get("stock"), 5)));
        }
        if ("0-10000".equals(criteria.priceRange())) {
            specs.add((root, query, cb) -> cb.lessThan(root.get("price"), BigDecimal.valueOf(10000)));
        } else if ("10000-50000".equals(criteria.priceRange())) {
            specs.add((root, query, cb) -> cb.between(root.get("price"), BigDecimal.valueOf(10000), BigDecimal.valueOf(50000)));
        } else if ("50000+".equals(criteria.priceRange())) {
            specs.add((root, query, cb) -> cb.greaterThan(root.get("price"), BigDecimal.valueOf(50000)));
        }

        return specs.stream().reduce(Specification::and).orElse(null);
    }

    private boolean isInStock(Product product) {
        if (product.getProductType() == ProductType.SIMPLE) {
            return product.getStock() > 0;
        }
        return product.getVariations().stream().anyMatch(v -> v.isActive() && v.getStock() > 0);
    }

    private Specification<Product> buildSpecification(ProductSearchCriteria criteria) {
        List<Specification<Product>> specs = new ArrayList<>();
        Long storeId = TenantContext.requireStoreId();
        specs.add((root, query, cb) -> cb.equal(root.get("store").get("id"), storeId));
        specs.add((root, query, cb) -> cb.isTrue(root.get("active")));

        if (criteria.search() != null && !criteria.search().isBlank()) {
            String term = "%" + criteria.search().toLowerCase() + "%";
            specs.add((root, query, cb) -> cb.or(
                    cb.like(cb.lower(root.get("name")), term),
                    cb.like(cb.lower(root.get("description")), term),
                    cb.like(cb.lower(root.get("sku")), term)
            ));
        }

        if (criteria.categorySlug() != null && !criteria.categorySlug().isBlank()) {
            specs.add((root, query, cb) -> {
                var categoryJoin = root.<Product, Category>join("category");
                return cb.equal(categoryJoin.get("slug"), criteria.categorySlug());
            });
        }

        if (criteria.type() != null) {
            specs.add((root, query, cb) -> cb.equal(root.get("productType"), criteria.type()));
        }

        if (criteria.featuredOnly()) {
            specs.add((root, query, cb) -> cb.isTrue(root.get("isFeatured")));
        }

        if (criteria.inStockOnly()) {
            specs.add((root, query, cb) -> cb.or(
                    cb.and(cb.equal(root.get("productType"), ProductType.SIMPLE), cb.greaterThan(root.get("stock"), 0)),
                    cb.equal(root.get("productType"), ProductType.VARIABLE)
            ));
        }

        if (criteria.newOnly()) {
            specs.add((root, query, cb) -> cb.isTrue(root.get("isNew")));
        }

        if (criteria.onSaleOnly()) {
            specs.add((root, query, cb) -> cb.isTrue(root.get("isOnSale")));
        }

        if (criteria.minPrice() != null) {
            specs.add((root, query, cb) -> cb.greaterThanOrEqualTo(root.get("price"), criteria.minPrice()));
        }

        if (criteria.maxPrice() != null) {
            specs.add((root, query, cb) -> cb.lessThanOrEqualTo(root.get("price"), criteria.maxPrice()));
        }

        return specs.stream().reduce(Specification::and).orElse(null);
    }
}
