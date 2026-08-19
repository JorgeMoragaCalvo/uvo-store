package org.uvo.uvostore.service.order;

import org.uvo.uvostore.entity.order.Order;

public interface OrderStatusService {
    // Ports PaymentController::handleCheckoutCompleted()/verifyPayment()'s "only if still
    // pending" guard — idempotent, safe to call more than once for the same order.
    Order markPaid(Long orderId, String stripePaymentIntentId);

    // Ports handlePaymentFailed().
    Order markPaymentFailed(Long orderId);

    // Ports handleCheckoutExpired()/handlePaymentCanceled() — both set the same
    // payment_status=failed/order_status=cancelled pair in the source app.
    Order markCancelled(Long orderId);
}
