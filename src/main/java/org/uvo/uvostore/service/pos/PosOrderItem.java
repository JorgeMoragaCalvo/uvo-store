package org.uvo.uvostore.service.pos;

import java.math.BigDecimal;

// productId es el id del Producto de UvoPOS vendido, no el nuestro: ProductSyncMapping.externalId en un producto
// simple, ProductVariationSyncMapping.externalVariantId en una variante (IMP-08: cada variante es un Producto POS).
// subtotal y taxAmount se añaden en G3.1: sin ellos, un documento emitido a partir de este payload no puede cuadrar
// con lo que se cobró. GAP-05: discountAmount es la parte del descuento de la orden que le toca a la línea; taxAmount,
// la del IVA.
public record PosOrderItem(Long productId, int quantity, BigDecimal price, BigDecimal subtotal,
                           BigDecimal discountAmount, BigDecimal taxAmount) {
}
