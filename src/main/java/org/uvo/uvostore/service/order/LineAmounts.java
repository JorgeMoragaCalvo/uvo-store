package org.uvo.uvostore.service.order;

import java.math.BigDecimal;

/**
 * GAP-05. Los montos de una línea del carrito tal como entran en la orden.
 *
 * @param unitPrice GAP-07: el precio unitario con que se cotizó. El checkout lo copia a la línea en vez de volver
 *                  a leer el producto, porque una escritura del POS o del panel entre las dos lecturas dejaría
 *                  líneas que no suman el total de la orden
 * @param subtotal  precio × cantidad, antes del descuento; con IVA si los precios lo incluyen
 * @param discount  la parte del descuento de la orden que le toca a la línea
 * @param tax       la parte del IVA de la orden que le toca a la línea: sumado o contenido según
 *                  {@link CartTotals#taxIncluded()}
 */
public record LineAmounts(BigDecimal unitPrice, BigDecimal subtotal, BigDecimal discount, BigDecimal tax) {
}
