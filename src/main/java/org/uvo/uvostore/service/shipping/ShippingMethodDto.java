package org.uvo.uvostore.service.shipping;

import java.util.Map;

// `credentialsSet` is redacted on the way out (values replaced with a boolean "is it set" instead
// of the real secret), same pattern as PaymentGatewayConfigDto.
public record ShippingMethodDto(
        Long id, String name, String code, String description, String type, boolean hasApiIntegration,
        String carrier, Map<String, Boolean> credentialsSet,
        Integer minDeliveryDays, Integer maxDeliveryDays, boolean active, int sortOrder, int ratesCount
) {
}
