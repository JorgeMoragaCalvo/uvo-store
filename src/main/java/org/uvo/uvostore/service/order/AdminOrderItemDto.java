package org.uvo.uvostore.service.order;

import java.math.BigDecimal;

/**
 * @param stockMissing GAP-03. La línea no se pudo descontar al confirmar el pago y la orden sigue bloqueada por eso.
 *                     Siempre false si la orden no está marcada, aunque la línea no lleve stock (manageStock=false).
 */
public record AdminOrderItemDto(
        Long id, Long productId, String productName, String productSku, Long variationId,
        int quantity, BigDecimal price, BigDecimal subtotal, boolean stockMissing
) {
}
