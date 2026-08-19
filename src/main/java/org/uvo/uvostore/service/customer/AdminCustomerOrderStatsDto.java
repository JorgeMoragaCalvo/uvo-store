package org.uvo.uvostore.service.customer;

import java.math.BigDecimal;

public record AdminCustomerOrderStatsDto(long totalOrders, BigDecimal totalSpent, BigDecimal averageOrder, long completedOrders) {
}
