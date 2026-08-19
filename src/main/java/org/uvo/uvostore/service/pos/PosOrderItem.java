package org.uvo.uvostore.service.pos;

import java.math.BigDecimal;

public record PosOrderItem(Long productId, int quantity, BigDecimal price) {
}
