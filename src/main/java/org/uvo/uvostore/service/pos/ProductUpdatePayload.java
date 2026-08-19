package org.uvo.uvostore.service.pos;

import java.util.Map;

public record ProductUpdatePayload(Long companyId, Long productId, String sku, Map<String, Object> data) {
}
