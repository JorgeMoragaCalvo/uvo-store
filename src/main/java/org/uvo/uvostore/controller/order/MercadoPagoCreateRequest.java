package org.uvo.uvostore.controller.order;

import jakarta.validation.constraints.NotNull;

public record MercadoPagoCreateRequest(@NotNull Long orderId, String successUrl, String failureUrl, String pendingUrl) {
}
