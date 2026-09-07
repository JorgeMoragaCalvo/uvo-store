package org.uvo.uvostore.service;

/**
 * A rule the caller broke, stated in words meant for them: a duplicate code, a coupon past its
 * limit, a category that still has products in it.
 *
 * <p>M1: these used to be {@code IllegalStateException}, which {@code GlobalExceptionHandler}
 * mapped to 400 with {@code ex.getMessage()} — and so did genuinely internal failures thrown as the
 * same type, like {@code "Error al crear sesión de pago: " + e.getMessage()} or
 * {@code "No se pudieron descifrar las credenciales"}. That cost twice over: Stripe's raw error
 * text reached the customer, and the failure never reached Sentry, because only the generic
 * {@code Exception} handler captures.
 *
 * <p>With the business cases wearing their own type, {@code IllegalStateException} goes back to
 * meaning "this shouldn't have happened" and lands where it belongs: a 500 with nothing leaked and
 * a Sentry report. Sibling of {@link org.uvo.uvostore.service.order.OutOfStockException} and
 * {@link org.uvo.uvostore.service.order.ShippingUnavailableException}, which answer 409 for the two
 * cases where the request was fine and the store simply couldn't serve it.
 */
public class BusinessException extends RuntimeException {

    public BusinessException(String message) {
        super(message);
    }
}
