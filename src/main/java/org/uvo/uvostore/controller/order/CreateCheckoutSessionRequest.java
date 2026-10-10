package org.uvo.uvostore.controller.order;

import jakarta.validation.constraints.NotNull;

/**
 * PROD-04. Ya no lleva {@code successUrl} ni {@code cancelUrl}: las construye el servidor desde la tienda
 * de la orden.
 *
 * <p>Aceptarlas era un redirect abierto en un flujo de pago — se pasaban a Stripe sin validar, así que
 * quien llamara elegía dónde acababa el pagador después de pagar. Un build antiguo de la SPA que todavía
 * las mande sigue funcionando: Jackson descarta las propiedades desconocidas.
 */
public record CreateCheckoutSessionRequest(@NotNull Long orderId) {
}
