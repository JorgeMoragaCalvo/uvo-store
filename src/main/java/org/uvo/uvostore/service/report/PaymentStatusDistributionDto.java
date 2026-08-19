package org.uvo.uvostore.service.report;

import java.math.BigDecimal;

public record PaymentStatusDistributionDto(String paymentStatus, long count, BigDecimal totalAmount) {
}
