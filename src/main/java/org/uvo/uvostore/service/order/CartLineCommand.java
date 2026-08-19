package org.uvo.uvostore.service.order;

public record CartLineCommand(
        Long productId, Long variationId, int quantity
) {
}
