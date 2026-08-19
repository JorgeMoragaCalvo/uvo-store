package org.uvo.uvostore.service.report;

import java.math.BigDecimal;

public record PaymentMethodDetailDto(
        String paymentMethod, long ordersCount, BigDecimal totalRevenue, long paidOrders, long pendingOrders,
        long failedOrders, BigDecimal averageOrderValue, double successRate
) {
}
