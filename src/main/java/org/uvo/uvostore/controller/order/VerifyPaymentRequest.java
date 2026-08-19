package org.uvo.uvostore.controller.order;

import jakarta.validation.constraints.NotBlank;

public record VerifyPaymentRequest(@NotBlank String sessionId) {
}
