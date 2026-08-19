package org.uvo.uvostore.service.report;

import java.math.BigDecimal;

public record PaymentMethodRevenueDto(String paymentMethod, long ordersCount, BigDecimal totalRevenue) {
}
