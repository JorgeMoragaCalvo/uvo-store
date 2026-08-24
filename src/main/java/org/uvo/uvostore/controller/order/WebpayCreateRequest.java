package org.uvo.uvostore.controller.order;

import jakarta.validation.constraints.NotNull;

public record WebpayCreateRequest(@NotNull Long orderId, String returnUrl) {
}
