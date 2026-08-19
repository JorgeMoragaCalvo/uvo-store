package org.uvo.uvostore.service.order;

import java.math.BigDecimal;

public record AdminOrderItemDto(
        Long id, Long productId, String productName, String productSku, Long variationId,
        int quantity, BigDecimal price, BigDecimal subtotal
) {
}
