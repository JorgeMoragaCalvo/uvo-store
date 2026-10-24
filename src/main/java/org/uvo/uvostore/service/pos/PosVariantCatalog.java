package org.uvo.uvostore.service.pos;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.uvo.uvostore.entity.catalog.Attribute;
import org.uvo.uvostore.entity.catalog.AttributeValue;
import org.uvo.uvostore.entity.catalog.Product;
import org.uvo.uvostore.entity.catalog.ProductVariation;
import org.uvo.uvostore.entity.catalog.ProductVariationAttribute;
import org.uvo.uvostore.entity.catalog.enums.AttributeType;
import org.uvo.uvostore.entity.pos.ProductSyncMapping;
import org.uvo.uvostore.entity.pos.ProductVariationSyncMapping;
import org.uvo.uvostore.entity.tenant.Store;
import org.uvo.uvostore.repository.AttributeRepository;
import org.uvo.uvostore.repository.AttributeValueRepository;
import org.uvo.uvostore.repository.ProductVariationAttributeRepository;
import org.uvo.uvostore.repository.ProductVariationRepository;
import org.uvo.uvostore.repository.ProductVariationSyncMappingRepository;
import org.uvo.uvostore.service.BusinessException;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * POS-5. Las variaciones de un producto del POS: crearlas al darlo de alta y ponerlas al día con cada entrada de
 * catálogo. Lo usan {@code PosSyncServiceImpl} (el alta) y {@link PosCatalogWriter} (todo lo demás).
 *
 * <p><b>Orden de cerrojos</b>: quien llama ya tiene bloqueado el producto padre (o acaba de crearlo). Todo lo que
 * escribe una variación bloquea antes a su padre —la venta, el webhook de stock, la conciliación y el panel—, así
 * que mientras dura esta transacción ninguna variación del producto cambia: por eso la suma de stock que se
 * calcula aquí es exacta, y por eso escribir el precio de una variación (que reescribe todas sus columnas, también
 * el stock) no pisa una venta.
 */
@Component
public class PosVariantCatalog {

    private final ProductVariationRepository variationRepository;
    private final ProductVariationSyncMappingRepository variantMappingRepository;
    private final AttributeRepository attributeRepository;
    private final AttributeValueRepository attributeValueRepository;
    private final ProductVariationAttributeRepository variationAttributeRepository;

    @PersistenceContext
    private EntityManager entityManager;

    public PosVariantCatalog(ProductVariationRepository variationRepository,
                             ProductVariationSyncMappingRepository variantMappingRepository,
                             AttributeRepository attributeRepository, AttributeValueRepository attributeValueRepository,
                             ProductVariationAttributeRepository variationAttributeRepository) {
        this.variationRepository = variationRepository;
        this.variantMappingRepository = variantMappingRepository;
        this.attributeRepository = attributeRepository;
        this.attributeValueRepository = attributeValueRepository;
        this.variationAttributeRepository = variationAttributeRepository;
    }

    /** El alta: una variación con su mapeo por cada variante. La lista ya está validada. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void createAll(Product product, ProductSyncMapping mapping, List<PosVariantFields> variants) {
        for (PosVariantFields variant : variants) {
            create(product, mapping, variant);
        }
    }

    /**
     * Pone las variaciones del producto al estado completo que manda el POS. La lista ya está validada y la
     * versión ya se comprobó ({@link PosCatalogWriter}).
     *
     * <ul>
     *   <li>Una variante conocida toma su precio si el mapeo tiene {@code syncPrice}, y vuelve a estar activa si se
     *       había quitado.</li>
     *   <li>Una desconocida se crea, con su stock (o 0) y sus atributos.</li>
     *   <li>Una mapeada que ya no viene se desactiva. No se borra: las órdenes la referencian. Su stock no se
     *       toca.</li>
     * </ul>
     *
     * <p>El stock de una variante que ya existe no se escribe nunca aquí, como el de un producto simple en POS-3:
     * lo mueven {@code stock.updated} y la conciliación, condicionados al valor visto.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void apply(Product product, ProductSyncMapping mapping, List<PosVariantFields> variants) {
        // refresh: la consulta devuelve la instancia que ya estuviera en la sesión sin releerla (la trampa de
        // PROD-13b), y quien llama pudo cargarla antes de tomar el cerrojo del padre.
        Map<Long, ProductVariationSyncMapping> known = new HashMap<>();
        for (ProductVariationSyncMapping variantMapping : variantMappingRepository.findByMappingId(mapping.getId())) {
            entityManager.refresh(variantMapping.getVariation());
            known.put(variantMapping.getExternalVariantId(), variantMapping);
        }

        Set<Long> incoming = new HashSet<>();
        for (PosVariantFields variant : variants) {
            incoming.add(variant.variantId());
            ProductVariationSyncMapping variantMapping = known.get(variant.variantId());
            if (variantMapping == null) {
                create(product, mapping, variant);
                continue;
            }
            ProductVariation variation = variantMapping.getVariation();
            if (mapping.isSyncPrice() && variation.getPrice().compareTo(variant.price()) != 0) {
                variation.setPrice(variant.price());
            }
            if (!variation.isActive()) {
                variation.setActive(true);
            }
        }
        for (ProductVariationSyncMapping variantMapping : known.values()) {
            if (!incoming.contains(variantMapping.getExternalVariantId()) && variantMapping.getVariation().isActive()) {
                variantMapping.getVariation().setActive(false);
            }
        }

        recalculateParent(product);
    }

    /**
     * El padre de un producto variable resume sus variaciones: el stock es la suma de todas, con la misma regla
     * que {@code ProductRepository.recalculateStockFromVariations} (si no, la venta y esto se corregirían el uno
     * al otro), y el precio es el de la variante activa más barata, que es el "desde" de la tienda.
     */
    private void recalculateParent(Product product) {
        List<ProductVariation> variations = variationRepository.findByProductId(product.getId());
        product.setStock(variations.stream().mapToInt(ProductVariation::getStock).sum());
        variations.stream()
                .filter(ProductVariation::isActive)
                .map(ProductVariation::getPrice)
                .min(BigDecimal::compareTo)
                .ifPresent(product::setPrice);
    }

    /**
     * POS-6. UNIQUE(store_id, sku) en product_variations: un SKU que ya usa otra variación de la tienda era un 500 por
     * la restricción, y en una carga masiva el POS lo reintentaría para siempre. Un 400 que nombra el SKU dice qué hay
     * que corregir en el POS. {@code PosSyncServiceImpl} lo llama antes de escribir nada; {@link #create} lo vuelve a
     * mirar para las variantes nuevas que llegan con una actualización.
     */
    public void requireFreeSkus(Store store, List<PosVariantFields> variants) {
        for (PosVariantFields variant : variants) {
            String sku = variant.sku().trim();
            if (variationRepository.existsByStoreIdAndSku(store.getId(), sku)) {
                throw new BusinessException("El SKU de variante " + sku + " ya existe en la tienda: corrígelo en el POS.");
            }
        }
    }

    private void create(Product product, ProductSyncMapping mapping, PosVariantFields variant) {
        Store store = product.getStore();
        requireFreeSkus(store, List.of(variant));
        String sku = variant.sku().trim();
        ProductVariation variation = new ProductVariation();
        variation.setStore(store);
        variation.setProduct(product);
        variation.setSku(sku);
        variation.setPrice(variant.price());
        variation.setStock(variant.stock() == null ? 0 : variant.stock());
        variation.setActive(true);
        // Las dos colecciones al día, y no solo las filas: quien lea el producto en esta misma sesión (la API de
        // la tienda dentro de un test, por ejemplo) tiene que ver la variación con sus atributos. Antes de
        // guardarla: si la colección del producto todavía no está cargada, cargarla después del INSERT la traería
        // ya, y quedaría dos veces.
        product.getVariations().add(variation);
        variationRepository.save(variation);

        for (Map.Entry<String, String> entry : variant.attributes().entrySet()) {
            Attribute attribute = attribute(store, entry.getKey().trim());
            ProductVariationAttribute link = new ProductVariationAttribute();
            link.setStore(store);
            link.setVariation(variation);
            link.setAttribute(attribute);
            link.setAttributeValue(value(attribute, entry.getValue().trim()));
            variation.getAttributeAssignments().add(variationAttributeRepository.save(link));
        }

        ProductVariationSyncMapping variantMapping = new ProductVariationSyncMapping();
        variantMapping.setMapping(mapping);
        variantMapping.setVariation(variation);
        variantMapping.setExternalVariantId(variant.variantId());
        variantMapping.setExternalSku(sku);
        variantMappingRepository.save(variantMapping);
    }

    // Por slug, como getOrCreateCategory desde POS-6: "Talla" y "talla" son el mismo atributo de la tienda.
    private Attribute attribute(Store store, String name) {
        String slug = PosVariants.slug(name);
        return attributeRepository.findByStoreIdAndSlug(store.getId(), slug).orElseGet(() -> {
            Attribute attribute = new Attribute();
            attribute.setStore(store);
            attribute.setName(name);
            attribute.setSlug(slug);
            attribute.setType(AttributeType.SELECT);
            return attributeRepository.save(attribute);
        });
    }

    private AttributeValue value(Attribute attribute, String text) {
        String slug = PosVariants.slug(text);
        return attributeValueRepository.findByAttributeIdAndSlug(attribute.getId(), slug).orElseGet(() -> {
            AttributeValue value = new AttributeValue();
            value.setAttribute(attribute);
            value.setStore(attribute.getStore());
            value.setValue(text);
            value.setSlug(slug);
            value.setSortOrder(attributeValueRepository.findByAttributeIdOrderBySortOrderAsc(attribute.getId()).size());
            return attributeValueRepository.save(value);
        });
    }
}
