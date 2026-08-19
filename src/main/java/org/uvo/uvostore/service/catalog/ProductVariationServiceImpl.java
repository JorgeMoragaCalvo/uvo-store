package org.uvo.uvostore.service.catalog;

import jakarta.transaction.Transactional;
import org.springframework.stereotype.Service;
import org.uvo.uvostore.entity.catalog.AttributeValue;
import org.uvo.uvostore.entity.catalog.Product;
import org.uvo.uvostore.entity.catalog.ProductVariation;
import org.uvo.uvostore.entity.catalog.ProductVariationAttribute;
import org.uvo.uvostore.repository.AttributeValueRepository;
import org.uvo.uvostore.repository.ProductRepository;
import org.uvo.uvostore.repository.ProductVariationAttributeRepository;
import org.uvo.uvostore.repository.ProductVariationRepository;

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

    public ProductVariationServiceImpl(ProductRepository productRepository, ProductVariationRepository variationRepository, AttributeValueRepository attributeValueRepository, ProductVariationAttributeRepository variationAttributeRepository) {
        this.productRepository = productRepository;
        this.variationRepository = variationRepository;
        this.attributeValueRepository = attributeValueRepository;
        this.variationAttributeRepository = variationAttributeRepository;
    }

    @Transactional
    public ProductVariation addVariation(Long productId, VariationCommand command) {
        Product product = productRepository.findById(productId)
                .orElseThrow(()-> new NoSuchElementException("Product " + productId + " not found"));

        ProductVariation variation = new ProductVariation();
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
                .orElseThrow(()-> new NoSuchElementException("Product variation " + variationId + " not found"));

        variation.setSku(command.sku());
        variation.setPrice(command.price());
        variation.setStock(command.stock());
        ProductVariation saved = variationRepository.save(variation);

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
                .orElseThrow(()-> new NoSuchElementException("Product variation " + variationId + " not found"));
        Long productId = variation.getProduct().getId();

        variationAttributeRepository.deleteByVariationId(variationId);
        variationRepository.delete(variation);

        recalculateParentAggregate(productId);
    }

    private void attachAttributeValues(ProductVariation variation, Map<Long, Long> attributeValueIdsByAttributeId) {

        for (Long attributeValueId : attributeValueIdsByAttributeId.values()){
            AttributeValue attributeValue = attributeValueRepository.findById(attributeValueId).orElse(null);
            if (attributeValue == null) continue;

            ProductVariationAttribute link = new ProductVariationAttribute();
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
                .orElseThrow(()-> new NoSuchElementException("Product " + productId + " not found"));
        product.setStock(totalStock);
        product.setPrice(minPrice);
        productRepository.save(product);
    }
}
