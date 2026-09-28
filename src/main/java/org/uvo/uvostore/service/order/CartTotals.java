package org.uvo.uvostore.service.order;

import org.uvo.uvostore.entity.order.Coupon;
import org.uvo.uvostore.service.shipping.ShippingOption;

import java.math.BigDecimal;

/**
 * @param shippingAvailable false when the store does ship but no zone covers the given
 *        region/commune. Without it a caller can't tell "shipping is free" from "we don't deliver
 *        there" — both used to surface as a cost of zero, which is how every order ended up with
 *        free shipping (A7).
 * @param couponApplied true only when a coupon code was supplied AND it was valid, so the
 *        storefront can say "that code is wrong" instead of silently showing no discount.
 * @param appliedCoupon F05. El cupón que de verdad entró en este total, o null. La decisión se toma
 *        <b>una sola vez</b>, aquí, y viaja con el precio: antes el checkout volvía a validar por su
 *        cuenta y, cuando su respuesta no coincidía con la del cálculo, se quedaba con el total
 *        rebajado y tiraba el cupón — descuento aplicado, cupón sin registrar y contador sin mover.
 * @param customerRejectionReason F05. Por qué el cupón no se pudo aplicar <b>a este cliente</b>, o
 *        null. Solo se rellena en los rechazos marcados como {@code customerSpecific}: son los que la
 *        cotización anónima del carrito no pudo prever, y por tanto los únicos en los que el cliente
 *        vería un precio y pagaría otro.
 */
public record CartTotals(
        BigDecimal subtotalWithoutTax, BigDecimal taxAmount, BigDecimal subtotalWithTax,
        BigDecimal shippingCost, BigDecimal discountAmount, BigDecimal total,
        boolean shippingAvailable, boolean couponApplied,
        Coupon appliedCoupon, String customerRejectionReason,
        // F16: la opción de envío que produjo `shippingCost`, o null si la tienda no envía. Viaja con el
        // precio por el mismo motivo que `appliedCoupon`: quien decide el importe es quien tiene que decir
        // con qué lo decidió. Antes solo sobrevivía el número, así que la orden no podía guardar qué
        // transportista ni qué tarifa lo respaldaron — y con ello los guardas de borrado de zonas y
        // métodos, que preguntan por órdenes asociadas, nunca encontraban ninguna.
        ShippingOption appliedShipping
) {
}
