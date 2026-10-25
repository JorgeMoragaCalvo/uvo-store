package org.uvo.uvostore.service.catalog;

import jakarta.transaction.Transactional;
import org.springframework.stereotype.Service;
import org.uvo.uvostore.entity.audit.enums.AuditAction;
import org.uvo.uvostore.entity.audit.enums.AuditTargetType;
import org.uvo.uvostore.entity.catalog.AttributeValue;
import org.uvo.uvostore.entity.catalog.Product;
import org.uvo.uvostore.entity.catalog.ProductVariation;
import org.uvo.uvostore.entity.catalog.ProductVariationAttribute;
import org.uvo.uvostore.repository.AttributeValueRepository;
import org.uvo.uvostore.repository.ProductRepository;
import org.uvo.uvostore.repository.ProductVariationAttributeRepository;
import org.uvo.uvostore.repository.ProductVariationRepository;
import org.uvo.uvostore.security.TenantContext;
import org.uvo.uvostore.service.audit.AuditDiff;
import org.uvo.uvostore.service.audit.AuditEntry;
import org.uvo.uvostore.service.audit.AuditTrail;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

@Service
public class ProductVariationServiceImpl implements ProductVariationService{

    private final ProductRepository productRepository;
    private final ProductVariationRepository variationRepository;
    private final AttributeValueRepository attributeValueRepository;
    private final ProductVariationAttributeRepository variationAttributeRepository;
    // needs a deleteByVariationId(Long) method, not yet listed in repositories.md
    private final PosCatalogOwnership posOwnership;
    private final AuditTrail auditTrail;

    public ProductVariationServiceImpl(ProductRepository productRepository, ProductVariationRepository variationRepository, AttributeValueRepository attributeValueRepository, ProductVariationAttributeRepository variationAttributeRepository, PosCatalogOwnership posOwnership, AuditTrail auditTrail) {
        this.productRepository = productRepository;
        this.variationRepository = variationRepository;
        this.attributeValueRepository = attributeValueRepository;
        this.variationAttributeRepository = variationAttributeRepository;
        this.posOwnership = posOwnership;
        this.auditTrail = auditTrail;
    }

    // POS-5: las tres escrituras bloquean el producto padre antes de tocar la variación, que es el orden de todo lo
    // que escribe variaciones (la venta en OrderInventoryService.decrementVariation). Con el orden contrario, editar
    // una variación en el panel y vender otra del mismo producto podían quedar esperándose mutuamente.
    @Transactional
    public ProductVariation addVariation(Long productId, VariationCommand command) {
        Product product = productRepository.findByIdForUpdate(productId)
                .filter(p -> p.getStore().getId().equals(TenantContext.requireStoreId()))
                .orElseThrow(()-> new NoSuchElementException("Product " + productId + " not found"));
        // POS-2: recalculateParentAggregate reescribiría el precio y el stock que gestiona UvoPOS.
        posOwnership.requireNotMapped(productId);

        ProductVariation variation = new ProductVariation();
        variation.setStore(TenantContext.requireCurrent());
        variation.setProduct(product);
        variation.setSku(command.sku());
        variation.setPrice(command.price());
        variation.setStock(command.stock());
        variation.setActive(true);
        ProductVariation saved = variationRepository.save(variation);

        attachAttributeValues(saved, command.attributeValueIdsByAttributeId());
        recalculateParentAggregate(productId);
        return saved;
    }

    @Transactional
    public ProductVariation updateVariation(Long variationId, VariationCommand command) {
        ProductVariation variation = variationRepository.findById(variationId)
                .filter(v -> v.getStore().getId().equals(TenantContext.requireStoreId()))
                .orElseThrow(()-> new NoSuchElementException("Product variation " + variationId + " not found"));
        // POS-2: el producto de la variante, no el {productId} de la ruta, que el controlador no usa para buscar.
        posOwnership.requireNotMapped(variation.getProduct().getId());
        productRepository.findByIdForUpdate(variation.getProduct().getId());

        BigDecimal previousPrice = variation.getPrice();
        variation.setSku(command.sku());
        variation.setPrice(command.price());
        variation.setStock(command.stock());
        ProductVariation saved = variationRepository.save(variation);
        // PROD-18. Solo el precio, como en el producto.
        AuditDiff diff = AuditDiff.create().value("price", previousPrice, saved.getPrice());
        if (diff.hasChanges()) {
            auditTrail.record(AuditEntry.of(AuditAction.VARIATION_PRICE_UPDATE, AuditTargetType.PRODUCT_VARIATION,
                            saved.getId())
                    .details(diff.with("productId", saved.getProduct().getId()).with("sku", saved.getSku())));
        }

        // Ports ProductVariations.php::updateVariation() — detach then reattach rather than a
        // diff/sync, matching the Laravel behavior exactly (detach() then re-attach() in a loop).
        variationAttributeRepository.deleteByVariationId(variationId);
        attachAttributeValues(saved, command.attributeValueIdsByAttributeId());

        recalculateParentAggregate(saved.getProduct().getId());
        return saved;
    }

    @Transactional
    public void deleteVariation(Long variationId) {
        ProductVariation variation = variationRepository.findById(variationId)
                .filter(v -> v.getStore().getId().equals(TenantContext.requireStoreId()))
                .orElseThrow(()-> new NoSuchElementException("Product variation " + variationId + " not found"));
        Long productId = variation.getProduct().getId();
        posOwnership.requireNotMapped(productId);
        productRepository.findByIdForUpdate(productId);

        variationAttributeRepository.deleteByVariationId(variationId);
        variationRepository.delete(variation);

        recalculateParentAggregate(productId);
    }

    private void attachAttributeValues(ProductVariation variation, Map<Long, Long> attributeValueIdsByAttributeId) {

        Long storeId = TenantContext.requireStoreId();
        for (Long attributeValueId : attributeValueIdsByAttributeId.values()){
            AttributeValue attributeValue = attributeValueRepository.findById(attributeValueId)
                    .filter(v -> v.getStore().getId().equals(storeId))
                    .orElse(null);
            if (attributeValue == null) continue;

            ProductVariationAttribute link = new ProductVariationAttribute();
            link.setStore(TenantContext.requireCurrent());
            link.setVariation(variation);
            link.setAttributeValue(attributeValue);
            link.setAttribute(attributeValue.getAttribute());
            variationAttributeRepository.save(link);
        }
    }

    @Override
    @Transactional
    public void recalculateParentAggregate(Long productId) {
        List<ProductVariation> variations = variationRepository.findByProductId(productId);

        int totalStock = variations.stream().mapToInt(ProductVariation::getStock).sum();
        BigDecimal minPrice = variations.stream()
                .map(ProductVariation::getPrice).min(BigDecimal::compareTo).orElse(BigDecimal.ZERO);

        Product product = productRepository.findById(productId)
                .filter(p -> p.getStore().getId().equals(TenantContext.requireStoreId()))
                .orElseThrow(()-> new NoSuchElementException("Product " + productId + " not found"));
        product.setStock(totalStock);
        product.setPrice(minPrice);
        productRepository.save(product);
    }
}
