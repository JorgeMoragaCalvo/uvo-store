package org.uvo.uvostore.service.pos;

public record StockAlertPayload(Long companyId, Long productId, String sku, int currentStock, int currentStockWeb, String alertType) {
}
