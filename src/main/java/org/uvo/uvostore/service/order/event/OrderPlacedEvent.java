package org.uvo.uvostore.service.order.event;

/**
 * El pedido se creó. Nada más: puede estar sin pagar, y lo normal es que lo esté.
 *
 * <p>F07. Antes esto se llamaba {@code OrderCompletedEvent} y de él colgaban los dos efectos de una
 * venta cerrada — la notificación al POS, que <b>emite un documento tributario</b>, y el correo de
 * "Gracias por tu compra"—, pero se publica al <b>crear</b> la orden, con el pago todavía pendiente.
 * Cada checkout abandonado emitía así una boleta de una venta que no ocurrió y le daba las gracias a
 * quien no había pagado. El nombre era la mitad del problema: decía "completed" de algo que solo
 * estaba empezando.
 *
 * <p>Ahora los efectos de venta cuelgan de {@link PaymentConfirmedEvent} y aquí queda lo único que de
 * verdad ha pasado en este punto: acusar recibo del pedido.
 *
 * <p>Lleva el id y no la entidad por lo mismo que {@link PaymentConfirmedEvent}: los oyentes corren
 * AFTER_COMMIT, fuera de la transacción que lo publicó.
 */
public record OrderPlacedEvent(Long orderId) {
}
