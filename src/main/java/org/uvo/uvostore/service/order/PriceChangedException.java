package org.uvo.uvostore.service.order;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.NumberFormat;
import java.util.Locale;

// GAP-07: entre que el comprador vio el total y confirmó, el precio cambió (una oferta que vence, un precio que el
// POS o el panel actualizan). El checkout se niega a cobrar una diferencia que nadie aceptó y no crea la orden; el
// SPA recotiza y muestra lo que cambió para que se acepte de nuevo.
//
// Hermana de OutOfStockException y ShippingUnavailableException, y se responde igual: 409, porque la petición está
// bien y es la tienda la que cambió.
public class PriceChangedException extends RuntimeException {

    public PriceChangedException(BigDecimal seen, BigDecimal current) {
        super("El total de tu pedido cambió de " + clp(seen) + " a " + clp(current)
                + ". Revisa el carrito y confirma de nuevo.");
    }

    private static String clp(BigDecimal amount) {
        return "$" + NumberFormat.getIntegerInstance(Locale.forLanguageTag("es-CL"))
                .format(amount.setScale(0, RoundingMode.HALF_UP));
    }
}
