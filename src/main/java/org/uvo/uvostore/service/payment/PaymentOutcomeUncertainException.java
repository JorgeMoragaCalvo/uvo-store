package org.uvo.uvostore.service.payment;

/**
 * IMP-07. La pasarela pudo haber cobrado y no sabemos: un timeout o un error 5xx al confirmar un pago.
 *
 * <p>No es un rechazo. Tratarlo como tal le decía al comprador «el pago fue rechazado» y lo devolvía al checkout,
 * donde pagaba otra vez: dos cobros por una compra. La orden queda pendiente con su token, la conciliación le
 * pregunta a la pasarela qué pasó ({@code PaymentReconciliationService}) y el comprador ve «pago en proceso».
 */
public class PaymentOutcomeUncertainException extends RuntimeException {

    private final String orderNumber;

    public PaymentOutcomeUncertainException(String orderNumber, String message, Throwable cause) {
        super(message, cause);
        this.orderNumber = orderNumber;
    }

    public String orderNumber() {
        return orderNumber;
    }
}
