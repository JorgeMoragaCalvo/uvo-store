package org.uvo.uvostore.service.payment;

public interface WebpayService {
    WebpayCreateResult createTransaction(Long orderId, String returnUrl);
    WebpayCommitResult commitTransaction(String token);

    /**
     * G1. Le pregunta a Transbank en qué quedó una transacción cuyo resultado nunca nos llegó, y si
     * está autorizada la da por pagada. Consulta {@code status()} y <b>nunca</b> {@code commit()}:
     * confirmar una transacción que el cliente abandonó le cobraría, que es justo lo contrario de lo
     * que una conciliación debe hacer.
     *
     * @return true si la orden quedó pagada en esta llamada.
     */
    boolean reconcile(Long orderId);
}
