package org.uvo.uvostore.service.catalog;

import org.uvo.uvostore.entity.catalog.ProductVariation;

public interface ProductVariationService {
    ProductVariation addVariation(Long productId, VariationCommand command);
    ProductVariation updateVariation(Long variationId, VariationCommand command);
    void deleteVariation(Long variationId);

    // Exposed so StockDecrementListener (service.order.event) can recompute the parent's
    // aggregate stock/price after a payment-confirmed stock decrement on one of its variations —
    // the same canonical aggregate rule add/update/deleteVariation already use, not a second
    // divergent "active-only, stock-only" variant like the Laravel listener had.
    void recalculateParentAggregate(Long productId);
}
