package org.uvo.uvostore.service.report;

import java.math.BigDecimal;

public record PaymentMethodsSummaryDto(long totalOrders, BigDecimal totalRevenue, long totalPaid, long totalPending, long totalFailed) {
}
