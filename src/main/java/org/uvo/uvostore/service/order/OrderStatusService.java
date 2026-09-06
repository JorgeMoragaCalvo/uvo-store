package org.uvo.uvostore.service.order;

import org.uvo.uvostore.entity.order.Order;

import java.math.BigDecimal;

public interface OrderStatusService {
    /**
     * Ports PaymentController::handleCheckoutCompleted()/verifyPayment()'s "only if still pending"
     * guard — idempotent, safe to call more than once for the same order.
     *
     * @param amountPaid what the gateway says actually arrived (M4). Checked against the order's
     *        total before anything is marked paid: no gateway compared it, they all just looked at
     *        the payment's *status*. The check lives here rather than in each of the five callers so
     *        none of them can skip it.
     */
    Order markPaid(Long orderId, String stripePaymentIntentId, BigDecimal amountPaid);

    // Ports handlePaymentFailed().
    Order markPaymentFailed(Long orderId);

    // Ports handleCheckoutExpired()/handlePaymentCanceled() — both set the same
    // payment_status=failed/order_status=cancelled pair in the source app.
    Order markCancelled(Long orderId);
}
