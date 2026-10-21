package org.uvo.uvostore.service.pos;

import java.util.Map;

// POS-3: version es la del producto en el POS, para descartar lo que llegue fuera de orden (PosCatalogWriter).
public record ProductUpdatePayload(Long companyId, Long productId, String sku, long version, Map<String, Object> data) {
}
