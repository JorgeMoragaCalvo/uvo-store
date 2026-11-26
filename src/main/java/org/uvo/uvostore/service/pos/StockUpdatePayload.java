package org.uvo.uvostore.service.pos;

// IMP-08. productId es un Producto del POS (un producto simple o una variante de la tienda). version es la del
// Producto en el POS (contrato §5); oldStock y newStock son el stock total de la bodega, solo para el registro.
public record StockUpdatePayload(Long companyId, Long productId, String sku, long version, Integer oldStock,
                                 Integer newStock, int stockWeb) {
}
