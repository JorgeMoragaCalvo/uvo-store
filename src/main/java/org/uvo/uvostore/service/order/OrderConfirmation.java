package org.uvo.uvostore.service.order;

import java.math.BigDecimal;

public record OrderConfirmation(Long orderId, String orderNumber, BigDecimal total) {
}
