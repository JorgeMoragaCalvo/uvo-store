package org.uvo.uvostore.service.shipping;

import java.util.List;

public record ShippingZoneCommand(
        String name, String description, List<String> regions, List<String> communes,
        boolean active, int sortOrder
) {
}
