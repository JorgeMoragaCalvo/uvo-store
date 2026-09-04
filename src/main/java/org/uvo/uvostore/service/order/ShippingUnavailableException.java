package org.uvo.uvostore.service.order;

// A7: the checkout used to accept any address and quietly price shipping at zero when no zone
// matched — the merchant absorbed the cost on every order without ever being told. Refusing is the
// honest answer: an order that can't be dispatched shouldn't be created.
//
// Enforced server-side, not just by hiding the button: the storefront now picks region and commune
// from the store's actual coverage, but a client calling the API directly would otherwise still be
// able to create free-shipping orders.
//
// Sibling of OutOfStockException (C5) and answered the same way: 409, because nothing is wrong with
// the request — the store simply doesn't deliver there.
public class ShippingUnavailableException extends RuntimeException {

    public ShippingUnavailableException(String region, String commune) {
        super("No hay envío disponible para " + describe(region, commune)
                + ". Elige otra dirección o contáctanos.");
    }

    private static String describe(String region, String commune) {
        if (commune != null && !commune.isBlank()) {
            return commune + (region != null && !region.isBlank() ? ", " + region : "");
        }
        return region != null && !region.isBlank() ? region : "la dirección indicada";
    }
}
