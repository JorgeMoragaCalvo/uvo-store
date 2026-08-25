package org.uvo.uvostore.service.shipping.carrier;

import org.springframework.stereotype.Component;
import org.uvo.uvostore.entity.shipping.ShippingMethod;
import org.uvo.uvostore.entity.shipping.enums.ShippingCarrier;
import org.uvo.uvostore.service.shipping.ShippingOption;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Component
public class ShippingCarrierQuoteDispatcher {

    private final Map<ShippingCarrier, ShippingCarrierQuoteClient> clientsByCarrier;

    public ShippingCarrierQuoteDispatcher(List<ShippingCarrierQuoteClient> clients) {
        this.clientsByCarrier = new EnumMap<>(ShippingCarrier.class);
        clients.forEach(client -> clientsByCarrier.put(client.carrier(), client));
    }

    public Optional<ShippingOption> quote(ShippingMethod method, ShippingCarrierQuoteRequest request) {
        if (method.getCarrier() == null) return Optional.empty();
        ShippingCarrierQuoteClient client = clientsByCarrier.get(method.getCarrier());
        return client == null ? Optional.empty() : client.quote(method, request);
    }
}
