package org.uvo.uvostore.service.pos;

import java.math.BigDecimal;

// productId es el identificador de UvoPOS (ProductSyncMapping.externalId), no el nuestro.
// subtotal y taxAmount se añaden en G3.1: sin ellos, un documento tributario emitido a partir de
// este payload no puede cuadrar con lo que se cobró.
public record PosOrderItem(Long productId, int quantity, BigDecimal price, BigDecimal subtotal, BigDecimal taxAmount) {
}
