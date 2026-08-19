package org.uvo.uvostore.service.order;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record AdminOrderDetailDto(
        Long id,
        String orderNumber,
        Long customerId,
        String customerEmail,
        String customerFirstName,
        String customerLastName,
        String customerPhone,
        String status,
        String paymentStatus,
        String fulfillmentStatus,
        BigDecimal subtotal,
        BigDecimal discountAmount,
        BigDecimal shippingCost,
        BigDecimal taxAmount,
        BigDecimal total,
        String paymentMethod,
        String trackingNumber,
        String trackingUrl,
        AddressDto shippingAddress,
        AddressDto billingAddress,
        String shippingRegion,
        String shippingCommune,
        String shippingPostalCode,
        String customerNotes,
        String notes,
        List<AdminOrderItemDto> items,
        Instant createdAt,
        Instant shippedAt,
        Instant deliveredAt
) {
}
