package org.uvo.uvostore.service.report;

import java.math.BigDecimal;

/**
 * @param totalRevenue lo que quedó: bruto menos reembolsos. Conserva el nombre porque es lo que el panel
 *        llama "Ingresos" y es la cifra que un comerciante quiere ver por defecto — pero F20 cambió lo
 *        que vale: antes era el bruto, y un reembolso parcial no lo bajaba.
 * @param grossRevenue lo cobrado antes de devoluciones.
 * @param refundedAmount lo devuelto, siempre positivo. Se informa aparte porque el neto por sí solo no
 *        dice si un mes flojo fue por vender poco o por devolver mucho.
 */
public record SalesSummaryDto(
        long totalOrders, BigDecimal totalRevenue, BigDecimal grossRevenue, BigDecimal refundedAmount,
        long totalItems, BigDecimal averageOrderValue,
        long paidOrders, long pendingOrders, long failedOrders
) {
}
