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
import java.util.List;
import java.util.Map;

@Service
public class PosSyncServiceImpl implements PosSyncService {

    private final ProductRepository productRepository;
    private final CategoryRepository categoryRepository;
    private final ProductSyncMappingRepository mappingRepository;
    private final PosCatalogWriter catalogWriter;
    private final PosVariantCatalog variantCatalog;
    private final PosInboundLog inboundLog;

    public PosSyncServiceImpl(ProductRepository productRepository, CategoryRepository categoryRepository,
                               ProductSyncMappingRepository mappingRepository, PosCatalogWriter catalogWriter,
                               PosVariantCatalog variantCatalog, PosInboundLog inboundLog) {
        this.productRepository = productRepository;
        this.categoryRepository = categoryRepository;
        this.mappingRepository = mappingRepository;
        this.catalogWriter = catalogWriter;
        this.variantCatalog = variantCatalog;
        this.inboundLog = inboundLog;
    }

    @Override
    @Transactional
    public SyncProductResult syncProduct(PosConnection connection, SyncProductCommand command) {
        // F01. La conexión llega ya autenticada por PosApiKeyAuthFilter y es la ÚNICA fuente de la
        // tienda (no TenantContext/subdominio: las llamadas del POS no vienen por uno).
        //
        // Antes esto la buscaba por el companyId del CUERPO, con un comentario que afirmaba que el
        // filtro lo había autenticado. No era cierto: el filtro autentica el de la CABECERA. Como
        // cada comercio se configura sus propias credenciales POS, bastaba firmar con las propias y
        // poner el companyId de otra tienda en el JSON para crear productos activos en su catálogo.
        Long companyId = connection.getCompanyId();
        Store store = connection.getStore();
        // POS-5. Antes de abrir el registro, como la validación del cuerpo: es un 400, la petición está mal formada.
        requireConsistent(command);
        SyncWebhookLog log = inboundLog.open(companyId, "product.sync", payloadOf(command));

        var existingMapping = mappingRepository.findByExternalIdAndCompanyId(command.externalId(), companyId);

        if (existingMapping.isPresent()) {
            // POS-3. Un producto que ya existe se actualiza por el mismo camino que el webhook product.updated:
            // solo lo que dicen los flags del mapeo, solo si la versión es más nueva y solo con el mapeo activo.
            // Antes se pisaba todo en cada envío —la descripción que escribió la web, el estado, la categoría—,
            // se escribía un stock absoluto que podía deshacer una venta web en vuelo, y un mapeo pausado
            // quedaba reactivado.
            //
            // El stock, el estado y la categoría ya no se tocan aquí: el stock entra por stock.updated,
            // condicionado al oldStock (F13), y el estado y la categoría son de la web (POS-2).
            ProductSyncMapping mapping = existingMapping.get();
            Long productId = mapping.getProduct().getId();
            PosCatalogWriter.Result result = catalogWriter.apply(mapping, command.version(),
                    new PosCatalogFields(command.name(), command.description(), command.price(), command.variants()));

            return switch (result.outcome()) {
                case APPLIED -> new SyncProductResult(productId, false, true, "Producto actualizado",
                        inboundLog.success(log).getId());
                case ALREADY_APPLIED, STALE -> new SyncProductResult(productId, false, false, result.message(),
                        inboundLog.discarded(log, result.message()).getId());
                case INACTIVE, MISMATCH -> new SyncProductResult(productId, false, false, result.message(),
                        inboundLog.failed(log, result.message()).getId());
            };
        }

        // La categoría solo se usa al crear, así que solo aquí se busca o se crea: antes se creaba en cada
        // envío aunque el producto ya existiera y no se le asignara.
        Category category = null;
        if (command.categoryName() != null && !command.categoryName().isBlank()) {
            category = getOrCreateCategory(store, command.categoryName());
        }

        // POS-5. Un producto con variantes nace VARIABLE, y su padre resume las variantes: el precio de la más
        // barata y la suma de los stocks, lo mismo que mantiene después PosVariantCatalog.
        boolean variable = command.variants() != null;
        Product product = new Product();
        product.setStore(store);
        product.setName(command.name());
        product.setSlug(PosCatalogWriter.slugify(command.name()));
        product.setSku(command.sku());
        product.setDescription(command.description() == null ? "" : command.description());
        product.setPrice(variable ? PosVariants.minPrice(command.variants()) : command.price());
        product.setStock(variable
                ? command.variants().stream().mapToInt(variant -> variant.stock() == null ? 0 : variant.stock()).sum()
                : command.stock());
        product.setWeight(BigDecimal.ZERO);
        product.setCategory(category);
        product.setActive(command.active() == null || command.active());
        product.setFeatured(false);
        product.setProductType(variable ? ProductType.VARIABLE : ProductType.SIMPLE);
        product.setManageStock(true);
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
        mapping.setSyncName(false);
        mapping.setSyncDescription(false);
        mapping.setCatalogVersion(command.version());
        mapping.setLastSyncedAt(Instant.now());
        mappingRepository.save(mapping);
        if (variable) {
            variantCatalog.createAll(saved, mapping, command.variants());
        }

        return new SyncProductResult(saved.getId(), true, true, "Producto creado", inboundLog.success(log).getId());
    }

    /**
     * POS-5. Las variantes cumplen las reglas de {@link PosVariants}, y un producto sin variantes trae su precio y su
     * stock, que ya no son obligatorios en el cuerpo porque un producto variable no los usa.
     */
    private static void requireConsistent(SyncProductCommand command) {
        if (command.variants() != null) {
            String invalid = PosVariants.validate(command.variants());
            if (invalid != null) {
                throw new BusinessException(invalid);
            }
        } else if (command.price() == null || command.stock() == null) {
            throw new BusinessException("Un producto sin variantes tiene que traer price y stock.");
        }
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
        if (command.variants() != null) {
            payload.put("variants", variantsPayload(command.variants()));
        }
        return payload;
    }

    // Para el registro: lo que hace falta para leerlo después, en el mismo formato que el resto del payload.
    static List<Map<String, Object>> variantsPayload(List<PosVariantFields> variants) {
        return variants.stream().map(variant -> {
            Map<String, Object> entry = new HashMap<>();
            entry.put("variant_id", variant.variantId());
            entry.put("sku", variant.sku());
            entry.put("price", variant.price());
            entry.put("stock", variant.stock());
            entry.put("attributes", variant.attributes());
            return entry;
        }).toList();
    }

    private Category getOrCreateCategory(Store store, String name) {
        return categoryRepository.findByStoreIdAndName(store.getId(), name).orElseGet(() -> {
            Category category = new Category();
            category.setStore(store);
            category.setName(name);
            category.setSlug(PosCatalogWriter.slugify(name));
            category.setDescription("Categoría sincronizada desde UvoPOS");
            category.setActive(true);
            return categoryRepository.save(category);
        });
    }
}
