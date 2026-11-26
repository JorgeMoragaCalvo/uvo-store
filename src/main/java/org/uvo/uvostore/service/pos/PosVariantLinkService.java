package org.uvo.uvostore.service.pos;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.uvo.uvostore.entity.catalog.Product;
import org.uvo.uvostore.entity.catalog.ProductVariation;
import org.uvo.uvostore.entity.catalog.enums.ProductType;
import org.uvo.uvostore.entity.pos.PosConnection;
import org.uvo.uvostore.entity.pos.ProductSyncMapping;
import org.uvo.uvostore.entity.pos.ProductVariationSyncMapping;
import org.uvo.uvostore.entity.pos.enums.SyncDirection;
import org.uvo.uvostore.entity.pos.enums.SyncStatus;
import org.uvo.uvostore.repository.PosConnectionRepository;
import org.uvo.uvostore.repository.ProductRepository;
import org.uvo.uvostore.repository.ProductSyncMappingRepository;
import org.uvo.uvostore.repository.ProductVariationRepository;
import org.uvo.uvostore.repository.ProductVariationSyncMappingRepository;
import org.uvo.uvostore.security.TenantContext;
import org.uvo.uvostore.service.BusinessException;

import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * IMP-08. El vínculo manual entre una variación de la tienda y su Producto del POS (contrato §4: el POS no tiene
 * variantes, cada una es un Producto suyo, y la agrupación es de la tienda). Decisión del usuario: se vincula a mano,
 * escribiendo el id del Producto POS en la variación.
 *
 * <p>Un mismo Producto del POS no puede ser dos cosas en una empresa: ni dos variaciones ni una variación y un producto
 * simple. La base garantiza lo primero ({@code UNIQUE (company_id, external_variant_id)}); lo segundo, que cruza dos
 * tablas, lo garantiza esto con un cerrojo por empresa y Producto.
 *
 * <p>El primer vínculo de un producto crea el mapeo de su padre, sin {@code external_id}: lleva la empresa y los
 * flags. El último que se quita lo borra.
 */
@Service
public class PosVariantLinkService {

    /** Primer entero del cerrojo {@code pg_advisory_xact_lock(int, int)}: aparte del de alta de tiendas (7312). */
    static final int LINK_LOCK_CLASS = 7313;

    private final ProductRepository productRepository;
    private final ProductVariationRepository variationRepository;
    private final ProductSyncMappingRepository mappingRepository;
    private final ProductVariationSyncMappingRepository variantMappingRepository;
    private final PosConnectionRepository connectionRepository;
    private final JdbcTemplate jdbcTemplate;

    public PosVariantLinkService(ProductRepository productRepository, ProductVariationRepository variationRepository,
                                 ProductSyncMappingRepository mappingRepository,
                                 ProductVariationSyncMappingRepository variantMappingRepository,
                                 PosConnectionRepository connectionRepository, JdbcTemplate jdbcTemplate) {
        this.productRepository = productRepository;
        this.variationRepository = variationRepository;
        this.mappingRepository = mappingRepository;
        this.variantMappingRepository = variantMappingRepository;
        this.connectionRepository = connectionRepository;
        this.jdbcTemplate = jdbcTemplate;
    }

    public record Link(Long variationId, Long posProductId) {
    }

    /** Los vínculos de las variaciones de un producto de la tienda actual. */
    @Transactional(readOnly = true)
    public List<Link> linksOf(Long productId) {
        Product product = productOfCurrentStore(productId);
        return variationRepository.findByProductId(product.getId()).stream()
                .flatMap(variation -> variantMappingRepository.findByVariationId(variation.getId()).stream())
                .map(vm -> new Link(vm.getVariation().getId(), vm.getExternalVariantId()))
                .toList();
    }

    @Transactional
    public Link link(Long productId, Long variationId, Long posProductId) {
        if (posProductId == null || posProductId <= 0) {
            throw new BusinessException("El id del Producto del POS no es válido.");
        }
        Product product = productOfCurrentStore(productId);
        ProductVariation variation = variationRepository.findById(variationId)
                .filter(found -> found.getProduct().getId().equals(product.getId()))
                .orElseThrow(() -> new NoSuchElementException("Product variation " + variationId + " not found"));
        if (product.getProductType() != ProductType.VARIABLE) {
            throw new BusinessException("Solo se vinculan las variantes de un producto con variantes.");
        }
        PosConnection connection = connectionRepository.findByStoreId(product.getStore().getId())
                .orElseThrow(() -> new BusinessException("La tienda no tiene conexión con UvoPOS."));
        Long companyId = connection.getCompanyId();

        // El padre primero, como todo lo que escribe variaciones; después el cerrojo del Producto del POS.
        productRepository.findByIdForUpdate(product.getId());
        jdbcTemplate.query("select pg_advisory_xact_lock(?, ?)", (ResultSetExtractor<Void>) rs -> null,
                LINK_LOCK_CLASS, Long.hashCode(companyId * 31 + posProductId));

        mappingRepository.findByExternalIdAndCompanyId(posProductId, companyId).ifPresent(simple -> {
            throw new BusinessException(("El Producto %d del POS ya es el producto «%s» de la tienda. Bórralo (o pide al "
                    + "POS que lo deje de cargar como producto aparte) antes de vincularlo como variante.")
                    .formatted(posProductId, simple.getProduct().getName()));
        });
        variantMappingRepository.findByCompanyIdAndExternalId(companyId, posProductId)
                .filter(other -> !other.getVariation().getId().equals(variationId))
                .ifPresent(other -> {
                    throw new BusinessException("El Producto %d del POS ya está vinculado a otra variante (SKU %s)."
                            .formatted(posProductId, other.getVariation().getSku()));
                });

        ProductSyncMapping parent = mappingRepository.findByProductIdAndCompanyId(product.getId(), companyId)
                .orElseGet(() -> mappingRepository.save(parentMapping(product, companyId)));
        ProductVariationSyncMapping link = variantMappingRepository.findByCompanyIdAndVariationId(companyId, variationId)
                .orElseGet(ProductVariationSyncMapping::new);
        if (posProductId.equals(link.getExternalVariantId())) {
            return new Link(variationId, posProductId);
        }
        link.setMapping(parent);
        link.setVariation(variation);
        link.setCompanyId(companyId);
        link.setExternalVariantId(posProductId);
        link.setExternalSku(variation.getSku());
        // Un Producto distinto empieza sin versión aplicada: el primer aviso o la conciliación lo ponen al día.
        link.setPosVersion(null);
        variantMappingRepository.save(link);
        return new Link(variationId, posProductId);
    }

    @Transactional
    public void unlink(Long productId, Long variationId) {
        Product product = productOfCurrentStore(productId);
        productRepository.findByIdForUpdate(product.getId());
        List<ProductVariationSyncMapping> links = variantMappingRepository.findByVariationId(variationId).stream()
                .filter(vm -> vm.getVariation().getProduct().getId().equals(product.getId()))
                .toList();
        for (ProductVariationSyncMapping link : links) {
            ProductSyncMapping parent = link.getMapping();
            variantMappingRepository.delete(link);
            variantMappingRepository.flush();
            if (parent.getExternalId() == null && variantMappingRepository.findByMappingId(parent.getId()).isEmpty()) {
                mappingRepository.delete(parent);
            }
        }
    }

    private Product productOfCurrentStore(Long productId) {
        return productRepository.findById(productId)
                .filter(p -> p.getStore().getId().equals(TenantContext.requireStoreId()))
                .orElseThrow(() -> new NoSuchElementException("Product " + productId + " not found"));
    }

    private static ProductSyncMapping parentMapping(Product product, Long companyId) {
        ProductSyncMapping mapping = new ProductSyncMapping();
        mapping.setProduct(product);
        mapping.setExternalId(null);
        mapping.setExternalSku(null);
        mapping.setCompanyId(companyId);
        mapping.setSyncStatus(SyncStatus.ACTIVE);
        mapping.setSyncDirection(SyncDirection.FROM_POS);
        mapping.setSyncStock(true);
        mapping.setSyncPrice(true);
        // El nombre y la descripción del padre son de la tienda: es la agrupación, no un Producto del POS.
        mapping.setSyncName(false);
        mapping.setSyncDescription(false);
        mapping.setLastSyncedAt(Instant.now());
        return mapping;
    }
}
