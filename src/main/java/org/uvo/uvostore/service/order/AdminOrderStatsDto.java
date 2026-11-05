package org.uvo.uvostore.service.order;

/**
 * @param stockShortage GAP-03. Órdenes cobradas sin stock que esperan una salida: la pestaña «Sin stock» del panel.
 * @param outboxFailed  GAP-04. Entregas del outbox (venta al POS, correos) que fallaron de forma definitiva: la
 *                      página «Envíos fallidos».
 */
public record AdminOrderStatsDto(long all, long pending, long paid, long processing, long shipped, long cancelled,
                                 long stockShortage, long outboxFailed) {
}
