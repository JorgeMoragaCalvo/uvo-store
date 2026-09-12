package org.uvo.uvostore.service.payment;

public interface MercadoPagoService {
    MercadoPagoPreferenceResult createPreference(
            Long orderId, String successUrl, String failureUrl, String pendingUrl, String notificationUrl);

    // Raw webhook body — MercadoPago's notification only tells us a payment id changed; we
    // re-fetch the payment from their API (authenticated with our own access token) rather than
    // trust anything in the payload itself, since this endpoint has no signature verification yet
    // (needs a real merchant account's webhook secret to add — see class javadoc on the impl).
    /**
     * @param signatureHeader `x-signature`, and `requestId` the `x-request-id` — both needed to
     *        verify MercadoPago's HMAC (M3). An unsigned or badly signed notification is rejected
     *        before the outbound API call it would otherwise trigger.
     */
    void handleWebhook(String payload, String signatureHeader, String requestId);

    /**
     * G1. Busca en MercadoPago los pagos de una orden cuyo webhook nunca llegó y, si hay uno
     * aprobado, la da por pagada. La búsqueda va por {@code external_reference} —el número de orden
     * que {@code createPreference} ya envía— porque es el único identificador que tenemos antes de
     * que llegue la notificación: el id del pago lo asigna MercadoPago.
     *
     * @return true si la orden quedó pagada en esta llamada.
     */
    boolean reconcile(Long orderId);

    /**
     * G4. Devuelve dinero de un pago de MercadoPago. Solo habla con la pasarela: cuánto se puede
     * devolver y qué pasa con la orden lo decide {@code RefundService}.
     *
     * @return el id del refund en MercadoPago, para poder rastrearlo desde el historial de la orden.
     */
    String refund(Long orderId, java.math.BigDecimal amount);
}
