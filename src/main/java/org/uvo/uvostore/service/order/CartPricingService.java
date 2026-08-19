package org.uvo.uvostore.service.order;

import java.util.List;

public interface CartPricingService {
    CartTotals price(List<CartLineCommand> lines, String couponCode, String region, String commune);
}
