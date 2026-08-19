package org.uvo.uvostore.service.report;

import java.math.BigDecimal;

public record ProductsSummaryDto(BigDecimal totalRevenue, long totalQuantity, long uniqueProducts, BigDecimal averagePrice) {
}
