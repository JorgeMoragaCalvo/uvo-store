package org.uvo.uvostore.service.order;

import java.math.BigDecimal;
import java.util.Map;

public record CartValidatedItemDto(
        Long id,
        String type,
        String name,
        Map<String, String> variation,
        BigDecimal price,
        int stock,
        String image,
        int maxQuantity
) {
}
