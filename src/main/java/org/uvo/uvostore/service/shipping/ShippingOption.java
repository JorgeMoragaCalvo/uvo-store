package org.uvo.uvostore.service.shipping;

import java.math.BigDecimal;

public record ShippingOption(
        Long methodId, String methodName, BigDecimal cost, String deliveryTime, boolean isFree
) {
}
