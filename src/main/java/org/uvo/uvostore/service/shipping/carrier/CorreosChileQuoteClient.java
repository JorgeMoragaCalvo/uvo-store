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
//
// PROD-12: la frase "a store can select CORREOS_CHILE as a method's carrier" ya no es cierta, y era el
// problema — ver isImplemented() abajo.
@Component
public class CorreosChileQuoteClient implements ShippingCarrierQuoteClient {

    private static final Logger log = LoggerFactory.getLogger(CorreosChileQuoteClient.class);

    @Override
    public ShippingCarrier carrier() {
        return ShippingCarrier.CORREOS_CHILE;
    }

    /**
     * PROD-12. Lo que convierte el comentario de arriba en algo comprobable.
     *
     * <p>Antes el aviso solo estaba en prosa, así que el panel ofrecía «Correos de Chile» en su
     * desplegable y un administrador podía seleccionarlo, guardar y activar el método. El checkout
     * simplemente nunca lo mostraba —{@code quote} devuelve vacío y una cotización vacía se omite—, así
     * que el comerciante creía haber habilitado un despacho que no existe y nada en la pantalla lo
     * contradecía. Devolviendo {@code false}, {@link ShippingCarrierPolicy} lo declara no disponible, la
     * administración rechaza configurarlo y el desplegable ni lo lista.
     *
     * <p>Quien implemente el {@code quote} de verdad: borra este método y ya está.
     */
    @Override
    public boolean isImplemented() {
        return false;
    }

    @Override
    public Optional<ShippingOption> quote(ShippingMethod method, ShippingCarrierQuoteRequest request) {
        log.debug("Correos de Chile aún no está implementado (método {}) — omitiendo del cotizador", method.getId());
        return Optional.empty();
    }
}
