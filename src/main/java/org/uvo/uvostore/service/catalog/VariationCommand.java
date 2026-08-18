package org.uvo.uvostore.service.catalog;

import java.math.BigDecimal;
import java.util.Map;

public record VariationCommand(
        Map<Long, Long> attributeValueIdsByAttributeId, BigDecimal price, Integer stock, String sku
) {
}
