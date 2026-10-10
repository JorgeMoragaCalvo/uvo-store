package org.uvo.uvostore.controller.order;

import jakarta.validation.constraints.NotNull;

/**
 * PROD-04. Ya no lleva las tres URLs de retorno: las construye el servidor desde la tienda de la orden.
 *
 * <p>Se pasaban a MercadoPago sin validar, igual que las de Stripe. Un build antiguo de la SPA que
 * todavía las mande sigue funcionando: Jackson descarta las propiedades desconocidas.
 */
public record MercadoPagoCreateRequest(@NotNull Long orderId) {
}
