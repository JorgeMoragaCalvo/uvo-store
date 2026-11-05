package org.uvo.uvostore.controller.admin.outbox;

/**
 * GAP-04. La nota y la confirmación se validan en {@code OutboxAdminService.retry}, no aquí, para que llamarlo desde
 * otro lado tampoco pueda saltárselas.
 *
 * @param posChecked el operador revisó en el POS que la venta no está; solo se exige en una venta incierta.
 */
public record OutboxRetryRequest(String note, Boolean posChecked) {
}
