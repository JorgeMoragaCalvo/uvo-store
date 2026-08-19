package org.uvo.uvostore.controller.admin.order;

import jakarta.validation.constraints.NotBlank;

public record TrackingRequest(@NotBlank String trackingNumber) {
}
