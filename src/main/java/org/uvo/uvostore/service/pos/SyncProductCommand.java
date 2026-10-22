package org.uvo.uvostore.service.pos;

import java.math.BigDecimal;
import java.util.List;

// F01: aquí ya no viaja ningún companyId. La empresa —y con ella la tienda— es la de la PosConnection
// autenticada, que se pasa aparte a syncProduct(). Quitar el campo del comando no es cosmético: es lo
// que hace imposible que un futuro llamador vuelva a resolver la tienda desde el cuerpo de la petición,
// que es exactamente como se llegó a que un comercio pudiera crear productos en el catálogo de otro.
//
// POS-3: stock, categoryName y active solo se usan al CREAR el producto. Después el stock entra por
// stock.updated y la categoría y el estado son de la web. version ordena las entradas (PosCatalogWriter).
//
// POS-5: variants es null en un producto simple. Con variantes, price y stock del padre no se usan (pueden no
// venir): los dos salen de sus variantes.
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
        List<PosVariantFields> variants
) {
}
