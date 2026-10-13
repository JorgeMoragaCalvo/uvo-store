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

    /**
     * PROD-12. Si detrás de esto hay una integración de verdad o solo un hueco con la forma correcta.
     *
     * <p>Vive aquí, y no en una lista dentro de {@link ShippingCarrierPolicy}, porque <b>es un hecho del
     * código y no de la configuración</b>: el día que alguien implemente Correos de Chile borra la
     * sobrescritura de esta clase y ya está, en vez de tener que acordarse de editar un catálogo en otro
     * archivo. Una lista aparte es como se desincronizan las dos mitades.
     *
     * <p>Que esté implementado no basta para ofrecerlo: eso lo decide {@link ShippingCarrierPolicy},
     * que además exige homologación. Un placeholder, en cambio, no se puede habilitar con ninguna
     * propiedad — no hay nada que habilitar.
     */
    default boolean isImplemented() {
        return true;
    }
}
