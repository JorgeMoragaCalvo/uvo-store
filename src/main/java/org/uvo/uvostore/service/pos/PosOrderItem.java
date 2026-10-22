package org.uvo.uvostore.service.pos;

import java.math.BigDecimal;

// productId es el identificador de UvoPOS (ProductSyncMapping.externalId), no el nuestro.
// subtotal y taxAmount se añaden en G3.1: sin ellos, un documento tributario emitido a partir de
// este payload no puede cuadrar con lo que se cobró.
// POS-5: variantId es la variante del POS (ProductVariationSyncMapping.externalVariantId) en una línea de
// variación, y null en una de producto simple.
public record PosOrderItem(Long productId, Long variantId, int quantity, BigDecimal price, BigDecimal subtotal,
                           BigDecimal taxAmount) {
}
