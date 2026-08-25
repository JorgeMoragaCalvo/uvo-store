package org.uvo.uvostore.shipping.carrier;

import org.junit.jupiter.api.Test;
import org.uvo.uvostore.entity.shipping.ShippingMethod;
import org.uvo.uvostore.entity.shipping.enums.ShippingCarrier;
import org.uvo.uvostore.service.shipping.carrier.ChilexpressQuoteClient;
import org.uvo.uvostore.service.shipping.carrier.CorreosChileQuoteClient;
import org.uvo.uvostore.service.shipping.carrier.ShippingCarrierQuoteRequest;

import java.math.BigDecimal;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Plain unit tests (no Spring context, no network) — verify the "never breaks checkout" contract:
// missing credentials, unmapped carrier, or an unimplemented carrier (Correos de Chile) must all
// degrade to Optional.empty() rather than throwing.
class ShippingCarrierQuoteClientTest {

    private final ChilexpressQuoteClient chilexpress = new ChilexpressQuoteClient("https://example.invalid/quote");
    private final CorreosChileQuoteClient correosChile = new CorreosChileQuoteClient();

    @Test
    void chilexpressReportsItsOwnCarrier() {
        assertEquals(ShippingCarrier.CHILEXPRESS, chilexpress.carrier());
    }

    @Test
    void chilexpressReturnsEmptyWhenSubscriptionKeyIsMissing() {
        ShippingMethod method = ShippingMethod.builder().id(1L).name("Chilexpress").build();
        ShippingCarrierQuoteRequest request = new ShippingCarrierQuoteRequest(
                "RM", "Santiago", "RM", "Providencia", BigDecimal.ONE, BigDecimal.valueOf(10000));

        assertTrue(chilexpress.quote(method, request).isEmpty());
    }

    @Test
    void chilexpressReturnsEmptyWhenDestinationCommuneIsMissing() {
        ShippingMethod method = ShippingMethod.builder().id(1L).name("Chilexpress")
                .apiCredentials(Map.of("subscriptionKey", "test-key", "originCountyCode", "STGO"))
                .build();
        ShippingCarrierQuoteRequest request = new ShippingCarrierQuoteRequest(
                "RM", "Santiago", "RM", null, BigDecimal.ONE, BigDecimal.valueOf(10000));

        assertTrue(chilexpress.quote(method, request).isEmpty());
    }

    @Test
    void chilexpressNeverThrowsWhenTheEndpointIsUnreachable() {
        ShippingMethod method = ShippingMethod.builder().id(1L).name("Chilexpress")
                .apiCredentials(Map.of("subscriptionKey", "test-key"))
                .build();
        ShippingCarrierQuoteRequest request = new ShippingCarrierQuoteRequest(
                "RM", "Santiago", "RM", "Providencia", BigDecimal.ONE, BigDecimal.valueOf(10000));

        assertTrue(chilexpress.quote(method, request).isEmpty());
    }

    @Test
    void correosChileReportsItsOwnCarrier() {
        assertEquals(ShippingCarrier.CORREOS_CHILE, correosChile.carrier());
    }

    @Test
    void correosChileAlwaysReturnsEmptyItIsAPlaceholder() {
        ShippingMethod method = ShippingMethod.builder().id(2L).name("Correos de Chile")
                .apiCredentials(Map.of("anything", "value"))
                .build();
        ShippingCarrierQuoteRequest request = new ShippingCarrierQuoteRequest(
                "RM", "Santiago", "RM", "Providencia", BigDecimal.ONE, BigDecimal.valueOf(10000));

        assertTrue(correosChile.quote(method, request).isEmpty());
    }
}
