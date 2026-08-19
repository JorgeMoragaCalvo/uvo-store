package org.uvo.uvostore.service.pos;

import java.math.BigDecimal;

public record SyncProductCommand(
        Long companyId,
        Long externalId,
        String sku,
        String name,
        String description,
        BigDecimal price,
        int stock,
        Long warehouseId,
        String categoryName,
        Boolean active
) {
}
