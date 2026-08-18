package org.uvo.uvostore.service.catalog;

import java.math.BigDecimal;

public record VariationCommand2(Long attributeValueIdByAttribute, BigDecimal price, Integer stock, String sku) {
}
