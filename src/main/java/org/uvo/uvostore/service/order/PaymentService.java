package org.uvo.uvostore.service.order;

public interface PaymentService {
    /**
     * PROD-04. Las URLs de retorno las construye la implementación desde la tienda de la orden. Antes se
     * recibían del cliente sin validar y se pasaban tal cual a Stripe, que es un redirect abierto dentro
     * de un flujo de pago real.
     */
    CheckoutSessionResult createCheckoutSession(Long orderId);
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
    // F12: la clave de idempotencia de la intención ya persistida, para que un reintento de la MISMA
    // devolución no mueva el dinero dos veces. Este SDK la admite; el de Transbank no.
    String refund(Long orderId, java.math.BigDecimal amount, String idempotencyKey);
}
