package org.uvo.uvostore.service.order.event;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.uvo.uvostore.service.pos.PosOrderNotifier;

// Ports NotifyPosOnOrderCompleted + the NotifyOrderToPOS job it dispatches (run inline, AFTER_COMMIT,
// instead of queued — same "no job queue infrastructure yet" gap already flagged for the POS
// webhook handlers in Fase 5).
//
// G2: la notificación en sí vive ahora en PosOrderNotifier, porque tiene un segundo llamador (el
// reintento programado). Esto se queda como lo que siempre fue — el enganche al evento.
@Component
public class PosNotificationListener {

    private final PosOrderNotifier notifier;

    public PosNotificationListener(PosOrderNotifier notifier) {
        this.notifier = notifier;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onOrderCompleted(OrderCompletedEvent event) {
        notifier.notifyOrder(event.orderId());
    }
}
