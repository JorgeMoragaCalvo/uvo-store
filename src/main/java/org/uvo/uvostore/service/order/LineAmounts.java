package org.uvo.uvostore.service.order;

import java.math.BigDecimal;

/**
 * GAP-05. Los montos de una línea del carrito tal como entran en la orden.
 *
 * @param subtotal precio × cantidad, antes del descuento; con IVA si los precios lo incluyen
 * @param discount la parte del descuento de la orden que le toca a la línea
 * @param tax      la parte del IVA de la orden que le toca a la línea: sumado o contenido según
 *                 {@link CartTotals#taxIncluded()}
 */
public record LineAmounts(BigDecimal subtotal, BigDecimal discount, BigDecimal tax) {
}
