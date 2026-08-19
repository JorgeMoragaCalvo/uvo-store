package org.uvo.uvostore.service.order;

import java.math.BigDecimal;
import java.time.Instant;

public record AdminOrderSummaryDto(
        Long id, String orderNumber, String customerEmail, String customerFirstName, String customerLastName,
        String status, String paymentStatus, String fulfillmentStatus, BigDecimal total, int itemsCount, Instant createdAt
) {
}
