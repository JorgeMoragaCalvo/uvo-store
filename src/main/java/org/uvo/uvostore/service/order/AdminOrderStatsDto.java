package org.uvo.uvostore.service.order;

/** @param stockShortage GAP-03. Órdenes cobradas sin stock que esperan una salida: la pestaña «Sin stock» del panel. */
public record AdminOrderStatsDto(long all, long pending, long paid, long processing, long shipped, long cancelled,
                                 long stockShortage) {
}
