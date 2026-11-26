package org.uvo.uvostore.service.pos;

import java.math.BigDecimal;

// F01: aquí ya no viaja ningún companyId. La empresa —y con ella la tienda— es la de la PosConnection
// autenticada, que se pasa aparte a syncProduct().
//
// POS-3: description, categoryName y active solo se usan al CREAR el producto; después son de la web. version ordena
// las entradas (PosCatalogWriter).
//
// IMP-08: un Producto del POS, sin variantes (contrato §4). stock es el stock web, y pasa por las mismas reglas que
// stock.updated (§6).
public record SyncProductCommand(
        Long externalId,
        String sku,
        String name,
        String description,
        BigDecimal price,
        Integer stock,
        Long warehouseId,
        String categoryName,
        Boolean active,
        long version,
        Boolean taxExempt
) {
}
