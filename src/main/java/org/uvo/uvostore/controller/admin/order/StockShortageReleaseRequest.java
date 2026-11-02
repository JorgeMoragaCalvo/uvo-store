package org.uvo.uvostore.controller.admin.order;

/**
 * GAP-03. La nota se valida en {@code AdminOrderServiceImpl.releaseStockShortage}, no aquí, para que llamarlo desde
 * otro lado tampoco pueda saltársela.
 */
public record StockShortageReleaseRequest(String note) {
}
