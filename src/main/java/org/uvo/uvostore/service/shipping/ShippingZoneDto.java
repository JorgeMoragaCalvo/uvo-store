package org.uvo.uvostore.service.shipping;

import java.util.List;

public record ShippingZoneDto(
        Long id, String name, String description, List<String> regions, List<String> communes,
        boolean active, int sortOrder
) {
}
