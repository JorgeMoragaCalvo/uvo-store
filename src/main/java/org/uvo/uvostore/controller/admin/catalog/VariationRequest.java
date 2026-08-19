package org.uvo.uvostore.controller.admin.catalog;

import java.math.BigDecimal;
import java.util.Map;

public record VariationRequest(
        Map<Long, Long> attributeValueIdsByAttributeId,
        BigDecimal price,
        Integer stock,
        String sku
) {
}
