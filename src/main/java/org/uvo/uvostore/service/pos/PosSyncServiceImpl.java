package org.uvo.uvostore.service.pos;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.uvo.uvostore.entity.catalog.Category;
import org.uvo.uvostore.entity.catalog.Product;
import org.uvo.uvostore.entity.catalog.enums.ProductType;
import org.uvo.uvostore.entity.pos.PosConnection;
import org.uvo.uvostore.entity.pos.ProductSyncMapping;
import org.uvo.uvostore.entity.pos.enums.SyncDirection;
import org.uvo.uvostore.entity.pos.enums.SyncStatus;
import org.uvo.uvostore.entity.tenant.Store;
import org.uvo.uvostore.repository.CategoryRepository;
import org.uvo.uvostore.repository.PosConnectionRepository;
import org.uvo.uvostore.repository.ProductRepository;
import org.uvo.uvostore.repository.ProductSyncMappingRepository;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.NoSuchElementException;

@Service
public class PosSyncServiceImpl implements PosSyncService {

    private final ProductRepository productRepository;
    private final CategoryRepository categoryRepository;
    private final ProductSyncMappingRepository mappingRepository;
    private final PosConnectionRepository posConnectionRepository;

    public PosSyncServiceImpl(ProductRepository productRepository, CategoryRepository categoryRepository,
                               ProductSyncMappingRepository mappingRepository, PosConnectionRepository posConnectionRepository) {
        this.productRepository = productRepository;
        this.categoryRepository = categoryRepository;
        this.mappingRepository = mappingRepository;
        this.posConnectionRepository = posConnectionRepository;
    }

    @Override
    @Transactional
    public SyncProductResult syncProduct(SyncProductCommand command) {
        // The request is already authenticated by PosApiKeyAuthFilter against this exact
        // companyId, so this connection is guaranteed to exist and be active — resolve its
        // store here (not via TenantContext/subdomain, since POS calls don't come through one).
        PosConnection connection = posConnectionRepository.findByCompanyId(command.companyId())
                .orElseThrow(() -> new NoSuchElementException("PosConnection " + command.companyId() + " not found"));
        Store store = connection.getStore();

        Category category = null;
        if (command.categoryName() != null && !command.categoryName().isBlank()) {
            category = getOrCreateCategory(store, command.categoryName());
        }

        var existingMapping = mappingRepository.findByExternalIdAndCompanyId(command.externalId(), command.companyId());

        if (existingMapping.isPresent()) {
            ProductSyncMapping mapping = existingMapping.get();
            Product product = mapping.getProduct();

            product.setName(command.name());
            product.setSlug(slugify(command.name()));
            product.setDescription(command.description() == null ? "" : command.description());
            product.setPrice(command.price());
            product.setStock(command.stock());
            product.setActive(command.active() == null || command.active());
            if (category != null) {
                product.setCategory(category);
            }
            productRepository.save(product);

            mapping.setSyncStatus(SyncStatus.ACTIVE);
            mapping.setSyncError(null);
            mapping.setLastSyncedAt(Instant.now());
            mappingRepository.save(mapping);

            return new SyncProductResult(product.getId(), false);
        }

        Product product = new Product();
        product.setStore(store);
        product.setName(command.name());
        product.setSlug(slugify(command.name()));
        product.setSku(command.sku());
        product.setDescription(command.description() == null ? "" : command.description());
        product.setPrice(command.price());
        product.setStock(command.stock());
        product.setWeight(BigDecimal.ZERO);
        product.setCategory(category);
        product.setActive(command.active() == null || command.active());
        product.setFeatured(false);
        product.setProductType(ProductType.SIMPLE);
        product.setManageStock(true);
        Product saved = productRepository.save(product);

        ProductSyncMapping mapping = new ProductSyncMapping();
        mapping.setProduct(saved);
        mapping.setExternalId(command.externalId());
        mapping.setExternalSku(command.sku());
        mapping.setCompanyId(command.companyId());
        mapping.setWarehouseId(command.warehouseId());
        mapping.setSyncStatus(SyncStatus.ACTIVE);
        mapping.setSyncDirection(SyncDirection.FROM_POS);
        mapping.setSyncStock(true);
        mapping.setSyncPrice(true);
        mapping.setSyncName(false);
        mapping.setSyncDescription(false);
        mapping.setLastSyncedAt(Instant.now());
        mappingRepository.save(mapping);

        return new SyncProductResult(saved.getId(), true);
    }

    private Category getOrCreateCategory(Store store, String name) {
        return categoryRepository.findByStoreIdAndName(store.getId(), name).orElseGet(() -> {
            Category category = new Category();
            category.setStore(store);
            category.setName(name);
            category.setSlug(slugify(name));
            category.setDescription("Categoría sincronizada desde UvoPOS");
            category.setActive(true);
            return categoryRepository.save(category);
        });
    }

    private static String slugify(String value) {
        return value.trim().toLowerCase()
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-|-$)", "");
    }
}
