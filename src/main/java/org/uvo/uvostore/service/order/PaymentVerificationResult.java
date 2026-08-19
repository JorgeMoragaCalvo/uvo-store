package org.uvo.uvostore.service.order;

public record PaymentVerificationResult(String sessionPaymentStatus, Long orderId, String orderNumber, String orderPaymentStatus) {
}
