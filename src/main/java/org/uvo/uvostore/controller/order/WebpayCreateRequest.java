package org.uvo.uvostore.controller.order;

import jakarta.validation.constraints.NotNull;

/**
 * PROD-04. Ya no lleva {@code returnUrl}: la construye el servidor desde la tienda de la orden.
 *
 * <p>Un build antiguo de la SPA que todavía lo mande sigue funcionando — Jackson descarta las propiedades
 * desconocidas — y ya no puede influir en dónde termina el pagador.
 */
public record WebpayCreateRequest(@NotNull Long orderId) {
}
