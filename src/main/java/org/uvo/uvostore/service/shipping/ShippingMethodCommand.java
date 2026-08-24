package org.uvo.uvostore.service.shipping;

public record ShippingMethodCommand(
        String name, String code, String description, String type, boolean hasApiIntegration, String carrier,
        Integer minDeliveryDays, Integer maxDeliveryDays, boolean active, int sortOrder
) {
}
