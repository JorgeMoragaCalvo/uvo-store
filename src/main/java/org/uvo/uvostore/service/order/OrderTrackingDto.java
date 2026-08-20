package org.uvo.uvostore.service.order;

import java.math.BigDecimal;
import java.time.Instant;

// Public, unauthenticated lookup — deliberately narrower than AdminOrderDetailDto:
// no customer PII beyond what the requester already supplied (the order number itself),
// no payment method/ids, no addresses.
public record OrderTrackingDto(
        String orderNumber,
        String status,
        String paymentStatus,
        String fulfillmentStatus,
        String trackingNumber,
        String trackingUrl,
        BigDecimal total,
        int itemsCount,
        Instant createdAt,
        Instant shippedAt,
        Instant deliveredAt
) {
}
