package org.uvo.uvostore.service.order;

public interface PaymentService {
    CheckoutSessionResult createCheckoutSession(Long orderId, String successUrl, String cancelUrl);
    PaymentVerificationResult verifyPayment(String sessionId);
    void handleWebhook(String payload, String signatureHeader);
}
