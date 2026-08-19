package org.uvo.uvostore.service.order.event;

// Ports App\Events\PaymentConfirmed — carries only the order id, not the entity, so listeners
// (which run AFTER_COMMIT, outside the publishing transaction) always reload a fresh, attached
// Order instead of risking a detached-entity/lazy-loading failure.
public record PaymentConfirmedEvent(Long orderId) {
}
