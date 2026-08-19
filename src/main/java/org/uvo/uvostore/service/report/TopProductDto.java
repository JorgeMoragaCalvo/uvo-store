package org.uvo.uvostore.service.report;

import java.math.BigDecimal;

public record TopProductDto(Long id, String name, long totalQuantity, BigDecimal totalRevenue, long ordersCount) {
}
