package org.uvo.uvostore.service.pos;

// POS-5. variantId es la variante del POS cuyo stock cambió, o null en un producto simple.
public record StockUpdatePayload(Long companyId, Long productId, Long variantId, String sku, int oldStock,
                                 int newStock, int stockWeb) {
}
