package org.uvo.uvostore.service.shipping.carrier;

import org.uvo.uvostore.entity.shipping.ShippingMethod;
import org.uvo.uvostore.entity.shipping.enums.ShippingCarrier;
import org.uvo.uvostore.service.shipping.ShippingOption;

import java.util.Optional;

// One implementation per ShippingCarrier. Implementations must NEVER throw — checkout can't fail
// because a carrier's API is down or unconfigured; on any problem (missing credentials, network
// error, unexpected response shape) return Optional.empty() so that method is simply omitted from
// the available shipping options, same as a ShippingRate that doesn't apply.
public interface ShippingCarrierQuoteClient {

    ShippingCarrier carrier();

    Optional<ShippingOption> quote(ShippingMethod method, ShippingCarrierQuoteRequest request);
}
