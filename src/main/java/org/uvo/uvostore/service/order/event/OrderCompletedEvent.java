package org.uvo.uvostore.service.order.event;

// Ports App\Events\OrderCompleted — see PaymentConfirmedEvent for why this carries an id, not
// the entity.
public record OrderCompletedEvent(Long orderId) {
}
