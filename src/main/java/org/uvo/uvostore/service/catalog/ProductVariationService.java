package org.uvo.uvostore.service.catalog;

import org.uvo.uvostore.entity.catalog.ProductVariation;

public interface ProductVariationService {
    ProductVariation addVariation(Long productId, VariationCommand command);
    ProductVariation updateVariation(Long variationId, VariationCommand command);
    void deleteVariation(Long variationId);
}
