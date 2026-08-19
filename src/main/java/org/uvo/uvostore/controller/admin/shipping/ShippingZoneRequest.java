package org.uvo.uvostore.controller.admin.shipping;

import jakarta.validation.constraints.NotBlank;

import java.util.List;

public record ShippingZoneRequest(
        @NotBlank String name,
        String description,
        List<String> regions,
        List<String> communes,
        boolean active,
        int sortOrder
) {
}
