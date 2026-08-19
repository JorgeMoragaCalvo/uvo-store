package org.uvo.uvostore.service.report;

import java.math.BigDecimal;

public record CategoryRevenueDto(Long id, String name, long productsCount, long totalQuantity, BigDecimal totalRevenue) {
}
