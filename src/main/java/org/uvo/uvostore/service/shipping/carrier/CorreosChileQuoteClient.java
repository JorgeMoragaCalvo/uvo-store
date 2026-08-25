package org.uvo.uvostore.service.shipping.carrier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.uvo.uvostore.entity.shipping.ShippingMethod;
import org.uvo.uvostore.entity.shipping.enums.ShippingCarrier;
import org.uvo.uvostore.service.shipping.ShippingOption;

import java.util.Optional;

// PLACEHOLDER — not a real integration. Correos de Chile's tarificador API contract (endpoint,
// auth scheme, request/response fields) isn't public/documented enough for this implementation to
// build against with any confidence (unlike Chilexpress's developers.wschilexpress.com portal), and
// no sandbox credentials were available to verify against. A store can select CORREOS_CHILE as a
// method's carrier and this class satisfies the ShippingCarrierQuoteClient contract so checkout
// keeps working (the method is just never offered), but it always returns empty until someone with
// real Correos de Chile API docs/credentials implements the actual HTTP call here — same shape as
// ChilexpressQuoteClient once that's available.
@Component
public class CorreosChileQuoteClient implements ShippingCarrierQuoteClient {

    private static final Logger log = LoggerFactory.getLogger(CorreosChileQuoteClient.class);

    @Override
    public ShippingCarrier carrier() {
        return ShippingCarrier.CORREOS_CHILE;
    }

    @Override
    public Optional<ShippingOption> quote(ShippingMethod method, ShippingCarrierQuoteRequest request) {
        log.debug("Correos de Chile aún no está implementado (método {}) — omitiendo del cotizador", method.getId());
        return Optional.empty();
    }
}
