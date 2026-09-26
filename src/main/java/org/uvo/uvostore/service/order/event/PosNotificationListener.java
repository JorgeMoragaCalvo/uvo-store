package org.uvo.uvostore.service.order.event;

import io.sentry.Sentry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.uvo.uvostore.config.AsyncConfig;
import org.uvo.uvostore.entity.tenant.Store;
import org.uvo.uvostore.repository.OrderRepository;
import org.uvo.uvostore.security.TenantContext;
import org.uvo.uvostore.service.pos.PosOrderNotifier;

// Ports NotifyPosOnOrderCompleted + the NotifyOrderToPOS job it dispatches (run AFTER_COMMIT instead
// of queued — same "no job queue infrastructure yet" gap already flagged for the POS webhook
// handlers in Fase 5).
//
// G2: la notificación en sí vive en PosOrderNotifier, porque tiene un segundo llamador (el reintento
// programado). Esto se queda como lo que siempre fue — el enganche al evento.
//
// R1: y ese enganche ya no corre en el hilo de la petición. Notificar al POS es una llamada de red a
// un tercero, y estaba metida dentro de la respuesta al comprador: un POS lento hacía lento el
// checkout, con el tope de read timeout de PosClient por cada empresa a la que hubiera que notificar.
// Ahora va al posExecutor, que tiene sus propios hilos y no comparte cola con el correo.
//
// F07: y cuelga del pago confirmado, no de la creación de la orden. Notificar al POS emite un
// DOCUMENTO TRIBUTARIO (ver application.properties, donde se explica por qué el reintento viene
// desactivado), y colgaba de un evento que se publica con la orden todavía en PENDING: cada checkout
// abandonado emitía la boleta de una venta que no llegó a ocurrir.
@Component
public class PosNotificationListener {

    private static final Logger log = LoggerFactory.getLogger(PosNotificationListener.class);

    private final OrderRepository orderRepository;
    private final PosOrderNotifier notifier;

    public PosNotificationListener(OrderRepository orderRepository, PosOrderNotifier notifier) {
        this.orderRepository = orderRepository;
        this.notifier = notifier;
    }

    @Async(AsyncConfig.POS_EXECUTOR)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPaymentConfirmed(PaymentConfirmedEvent event) {
        try {
            // El tenant es un ThreadLocal y no cruza al hilo del executor, así que se resuelve desde
            // la orden —igual que hace el reintento programado— y no desde el hilo. Hoy PosOrderNotifier
            // no lo pide, pero cualquier cosa que se añada ahí dentro y sí lo haga (leer credenciales
            // de la tienda, por ejemplo) reventaría sin esto.
            Store store = orderRepository.findStoreByOrderId(event.orderId()).orElse(null);
            TenantContext.runWithin(store, () -> notifier.notifyOrder(event.orderId()));
        } catch (Exception e) {
            // En un hilo de pool no hay llamador a quien relanzarle nada: o se registra aquí, o el
            // fallo se pierde en el manejador por defecto de Spring y no llega a Sentry.
            log.error("Error notificando la orden al POS order_id={} error={}", event.orderId(), e.getMessage());
            Sentry.captureException(e);
        }
    }
}
