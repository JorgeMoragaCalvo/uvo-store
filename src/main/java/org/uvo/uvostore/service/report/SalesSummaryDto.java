package org.uvo.uvostore.service.report;

import java.math.BigDecimal;

public record SalesSummaryDto(
        long totalOrders, BigDecimal totalRevenue, long totalItems, BigDecimal averageOrderValue,
        long paidOrders, long pendingOrders, long failedOrders
) {
}
