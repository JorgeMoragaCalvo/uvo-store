package org.uvo.uvostore.service.payment;

public record WebpayCommitResult(Long orderId, String orderNumber, String transbankStatus, String orderPaymentStatus) {
}
