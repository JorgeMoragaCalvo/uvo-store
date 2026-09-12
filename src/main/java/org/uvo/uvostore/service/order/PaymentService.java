package org.uvo.uvostore.service.order;

public interface PaymentService {
    CheckoutSessionResult createCheckoutSession(Long orderId, String successUrl, String cancelUrl);
    PaymentVerificationResult verifyPayment(String sessionId);
    void handleWebhook(String payload, String signatureHeader);

    /**
     * G4. Devuelve dinero de una orden cobrada por Stripe. Solo habla con la pasarela: quien decide
     * cuánto se puede devolver y quién marca la orden es {@code RefundService}.
     *
     * @param amount cuánto devolver, siempre explícito — el llamador ya resolvió si es el total o
     *        una parte.
     * @return el id del refund en Stripe, para poder rastrearlo desde el historial de la orden.
     */
    String refund(Long orderId, java.math.BigDecimal amount);
}
