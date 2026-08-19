package org.uvo.uvostore.service.order;

import java.util.List;

public interface CartService {
    // Ports CartController::validate().
    CartValidationResult validateItems(List<CartItemCommand> items);

    // Ports CartController::calculate() but reuses CartPricingService (the single authoritative
    // calculator) instead of the controller's own duplicate subtotal/shipping/tax math.
    CartCalculationResult calculate(List<CartItemCommand> items, String region, String commune, String couponCode);
}
