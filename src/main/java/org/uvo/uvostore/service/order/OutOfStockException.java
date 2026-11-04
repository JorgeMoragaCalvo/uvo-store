package org.uvo.uvostore.service.order;

import java.util.Map;

// C5: the checkout now refuses to create an order it can't fulfil, instead of letting the customer
// pay and discovering the problem afterwards. A dedicated exception so GlobalExceptionHandler can
// answer 409 Conflict — the generic IllegalStateException handler returns 400, which doesn't tell
// the SPA apart "you sent something invalid" from "someone bought it first".
//
// `errors` is the per-item map that CartServiceImpl.validateItems already produces (keyed
// "items.0", "items.1", …), so the response has the same shape the cart validation endpoint
// returns and the SPA can render it with the code it already has.
public class OutOfStockException extends RuntimeException {

    private final transient Map<String, String> errors;

    public OutOfStockException(Map<String, String> errors) {
        this("Uno o más productos no tienen stock suficiente", errors);
    }

    // GAP-06. El mismo 409 por línea cuando lo que falla no es el stock sino el POS: productos de dos empresas, o
    // sin mapeo. El SPA muestra este mensaje, y "no tienen stock suficiente" habría sido falso.
    public OutOfStockException(String message, Map<String, String> errors) {
        super(message);
        this.errors = errors;
    }

    public Map<String, String> getErrors() {
        return errors;
    }
}
