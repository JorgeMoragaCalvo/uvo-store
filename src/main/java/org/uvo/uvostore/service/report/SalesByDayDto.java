package org.uvo.uvostore.service.report;

import java.math.BigDecimal;

public record SalesByDayDto(String date, long ordersCount, BigDecimal revenue, long paidOrders) {
}
