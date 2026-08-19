package org.uvo.uvostore.service.pos;

public record StockUpdatePayload(Long companyId, Long productId, String sku, int oldStock, int newStock, int stockWeb) {
}
