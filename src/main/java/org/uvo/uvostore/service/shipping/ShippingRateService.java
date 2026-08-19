package org.uvo.uvostore.service.shipping;

import org.uvo.uvostore.service.order.CartLineCommand;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

public interface ShippingRateService {
    List<ShippingOption> getAvailableOptions(String region, String commune, BigDecimal orderAmount, BigDecimal totalWeight);
    Optional<ShippingOption> getBestOption(String region, String commune, BigDecimal orderAmount, BigDecimal totalWeight);
    BigDecimal calculateTotalWeight(List<CartLineCommand> lines);
}
