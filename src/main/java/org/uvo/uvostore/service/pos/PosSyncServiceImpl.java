package org.uvo.uvostore.service.pos;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.uvo.uvostore.entity.catalog.Category;
import org.uvo.uvostore.entity.catalog.Product;
import org.uvo.uvostore.entity.catalog.enums.ProductType;
import org.uvo.uvostore.entity.pos.PosConnection;
import org.uvo.uvostore.entity.pos.ProductSyncMapping;
import org.uvo.uvostore.entity.pos.SyncWebhookLog;
import org.uvo.uvostore.entity.pos.enums.SyncDirection;
import org.uvo.uvostore.entity.pos.enums.SyncStatus;
import org.uvo.uvostore.entity.tenant.Store;
import org.uvo.uvostore.repository.CategoryRepository;
import org.uvo.uvostore.repository.ProductRepository;
import org.uvo.uvostore.repository.ProductSyncMappingRepository;
import org.uvo.uvostore.service.BusinessException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * La carga de un Producto del POS ({@code POST /api/sync/product}, contrato §5).
 *
 * <p>IMP-08: si el Producto ya está en la tienda —un producto simple mapeado o una variante vinculada desde el panel—
 * se actualiza por el mismo camino que los avisos ({@link PosCatalogWriter}): solo lo que es del POS, solo si la
 * versión es más nueva y con el stock protegido (§6). Si no está, se crea como producto simple. Una variante no se
 * crea nunca desde aquí: la agrupación en variantes es de la tienda (§4).
 */
@Service
public class PosSyncServiceImpl implements PosSyncService {

    private final ProductRepository productRepository;
    private final CategoryRepository categoryRepository;
    private final ProductSyncMappingRepository mappingRepository;
    private final PosProductResolver resolver;
    private final PosCatalogWriter catalogWriter;
    private final PosInboundLog inboundLog;

    public PosSyncServiceImpl(ProductRepository productRepository, CategoryRepository categoryRepository,
                              ProductSyncMappingRepository mappingRepository, PosProductResolver resolver,
                              PosCatalogWriter catalogWriter, PosInboundLog inboundLog) {
        this.productRepository = productRepository;
        this.categoryRepository = categoryRepository;
        this.mappingRepository = mappingRepository;
        this.resolver = resolver;
        this.catalogWriter = catalogWriter;
        this.inboundLog = inboundLog;
    }

    @Override
    @Transactional
    public SyncProductResult syncProduct(PosConnection connection, SyncProductCommand command) {
        // F01. La conexión llega ya autenticada por PosSignatureAuthFilter y es la ÚNICA fuente de la tienda.
        Long companyId = connection.getCompanyId();
        Store store = connection.getStore();
        SyncWebhookLog log = inboundLog.open(companyId, "product.sync", payloadOf(command));

        Optional<PosTarget> existing = resolver.resolve(companyId, command.externalId());
        if (existing.isEmpty() && mappingRepository.findByExternalIdAndCompanyId(command.externalId(), companyId).isPresent()) {
            // El id está en el mapeo de un producto con variantes (anterior a IMP-08): no es un Producto del POS.
            String message = ("El Producto %d del POS está mapeado a un producto con variantes de la tienda: vincula "
                    + "cada variante por separado.").formatted(command.externalId());
            return new SyncProductResult(null, false, false, message, inboundLog.failed(log, message).getId());
        }
        if (existing.isPresent()) {
            PosTarget target = existing.get();
            PosCatalogWriter.Result result = catalogWriter.apply(target, command.version(),
                    new PosCatalogFields(command.name(), command.price(), command.taxExempt(), command.stock()));
            Long productId = target.productId();
            return switch (result.outcome()) {
                case APPLIED -> new SyncProductResult(productId, false, true,
                        result.message() == null ? "Producto actualizado" : result.message(),
                        inboundLog.success(log, result.message()).getId());
                case ALREADY_APPLIED, STALE -> new SyncProductResult(productId, false, false, result.message(),
                        inboundLog.discarded(log, result.message()).getId(), true);
                case INACTIVE, MISMATCH -> new SyncProductResult(productId, false, false, result.message(),
                        inboundLog.failed(log, result.message()).getId());
            };
        }

        // POS-6. UNIQUE(store_id, sku): un SKU que ya usa otro producto de la tienda era un 500 por la restricción, y
        // en la carga inicial del catálogo el POS lo reintentaría para siempre. Un 400 que nombra el SKU dice qué
        // corregir.
        if (command.sku() != null && !command.sku().isBlank()
                && productRepository.existsByStoreIdAndSku(store.getId(), command.sku())) {
            throw new BusinessException("El SKU " + command.sku() + " ya existe en la tienda: corrígelo en el POS.");
        }

        // La categoría solo se usa al crear, así que solo aquí se busca o se crea.
        Category category = null;
        if (command.categoryName() != null && !command.categoryName().isBlank()) {
            category = getOrCreateCategory(store, command.categoryName());
        }

        Product product = new Product();
        product.setStore(store);
        product.setName(command.name());
        product.setSlug(catalogWriter.uniqueProductSlug(store.getId(), command.name(), null));
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
        product.setTaxExempt(Boolean.TRUE.equals(command.taxExempt()));
        Product saved = productRepository.save(product);

        ProductSyncMapping mapping = new ProductSyncMapping();
        mapping.setProduct(saved);
        mapping.setExternalId(command.externalId());
        mapping.setExternalSku(command.sku());
        mapping.setCompanyId(companyId);
        mapping.setWarehouseId(command.warehouseId());
        mapping.setSyncStatus(SyncStatus.ACTIVE);
        mapping.setSyncDirection(SyncDirection.FROM_POS);
        mapping.setSyncStock(true);
        mapping.setSyncPrice(true);
        // IMP-08. El nombre es del POS (§4): queda bloqueado en el panel y lo actualizan sus avisos.
        mapping.setSyncName(true);
        mapping.setSyncDescription(false);
        mapping.setCatalogVersion(command.version());
        mapping.setLastSyncedAt(Instant.now());
        mappingRepository.save(mapping);

        return new SyncProductResult(saved.getId(), true, true, "Producto creado", inboundLog.success(log).getId());
    }

    // HashMap y no Map.of: la descripción, la bodega, la categoría y el estado pueden no venir.
    private static Map<String, Object> payloadOf(SyncProductCommand command) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("external_id", command.externalId());
        payload.put("sku", command.sku());
        payload.put("version", command.version());
        payload.put("name", command.name());
        payload.put("description", command.description());
        payload.put("price", command.price());
        payload.put("stock", command.stock());
        payload.put("warehouse_id", command.warehouseId());
        payload.put("category_name", command.categoryName());
        payload.put("active", command.active());
        payload.put("tax_exempt", command.taxExempt());
        return payload;
    }

    // POS-6. Por nombre y, si no, por slug: "Bebidas" y "bebidas" son la misma categoría.
    private Category getOrCreateCategory(Store store, String name) {
        String slugified = PosCatalogWriter.slugify(name);
        String slug = slugified.isEmpty() ? "categoria" : slugified;
        return categoryRepository.findByStoreIdAndName(store.getId(), name)
                .or(() -> categoryRepository.findByStoreIdAndSlug(store.getId(), slug))
                .orElseGet(() -> {
                    Category category = new Category();
                    category.setStore(store);
                    category.setName(name);
                    category.setSlug(slug);
                    category.setDescription("Categoría sincronizada desde UvoPOS");
                    category.setActive(true);
                    return categoryRepository.save(category);
                });
    }
}
