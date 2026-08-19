package org.uvo.uvostore.service.shipping;

public record ShippingMethodDto(
        Long id, String name, String code, String description, String type, boolean hasApiIntegration,
        Integer minDeliveryDays, Integer maxDeliveryDays, boolean active, int sortOrder, int ratesCount
) {
}
