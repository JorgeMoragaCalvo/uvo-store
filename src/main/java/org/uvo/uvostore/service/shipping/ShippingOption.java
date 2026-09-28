package org.uvo.uvostore.service.shipping;

import java.math.BigDecimal;

/**
 * @param rateId F16. La {@code ShippingRate} que produjo este precio, para que la orden pueda dejar
 *        escrito con qué se cotizó. <b>Nulo en las cotizaciones de transportista</b> (Chilexpress y
 *        compañía), que no salen de la tabla de tarifas sino de una llamada en vivo: ahí el método se
 *        conoce, la tarifa no existe.
 */
public record ShippingOption(
        Long methodId, String methodName, BigDecimal cost, String deliveryTime, boolean isFree, Long rateId
) {

    /** Para las cotizaciones en vivo, que no tienen una tarifa en la tabla a la que apuntar. */
    public ShippingOption(Long methodId, String methodName, BigDecimal cost, String deliveryTime, boolean isFree) {
        this(methodId, methodName, cost, deliveryTime, isFree, null);
    }
}
