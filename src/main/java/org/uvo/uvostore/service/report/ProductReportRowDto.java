package org.uvo.uvostore.service.report;

import java.math.BigDecimal;

public record ProductReportRowDto(
        Long id, String name, String sku, BigDecimal currentPrice, int stock, String categoryName,
        long totalQuantity, BigDecimal totalRevenue, long totalOrders, BigDecimal averagePrice
) {
}
