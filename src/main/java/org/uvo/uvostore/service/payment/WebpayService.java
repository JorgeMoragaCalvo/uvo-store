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

    /**
     * G4. Devuelve dinero de una transacción de Webpay. Solo habla con Transbank: cuánto se puede
     * devolver y qué pasa con la orden lo decide {@code RefundService}.
     *
     * @return el tipo que devuelve Transbank: {@code REVERSED} si anuló la transacción (solo cabe el
     *         mismo día, antes del cierre) o {@code NULLIFIED} si fue un reembolso. Se guarda porque
     *         son operaciones distintas, con plazos distintos, y desde fuera se piden igual.
     */
    String refund(Long orderId, java.math.BigDecimal amount);
}
