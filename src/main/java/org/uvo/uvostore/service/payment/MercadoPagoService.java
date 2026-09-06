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
}
