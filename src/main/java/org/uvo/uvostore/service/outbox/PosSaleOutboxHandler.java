package org.uvo.uvostore.service.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.uvo.uvostore.entity.outbox.OutboxMessage;
import org.uvo.uvostore.entity.outbox.enums.OutboxType;
import org.uvo.uvostore.service.pos.PosClient;
import org.uvo.uvostore.service.pos.PosDelivery;
import org.uvo.uvostore.service.pos.PosOrderNotifier;
import org.uvo.uvostore.service.pos.PosSaleRequest;

/**
 * PROD-13b. Registra una venta en una empresa del POS: una fila {@code POS_ORDER} del outbox.
 *
 * <p>Ocupa el lugar de {@code PosNotificationListener}. F07 sigue en pie: la fila solo se escribe al
 * confirmar el pago, así que una orden sin cobrar no emite documento. Y PROD-12 también: con la integración
 * apagada no se encola nada, y si se apaga después, {@link PosOrderNotifier#prepare} la omite.
 */
@Component
public class PosSaleOutboxHandler implements OutboxHandler {

    private static final Logger log = LoggerFactory.getLogger(PosSaleOutboxHandler.class);

    private final PosOrderNotifier notifier;
    private final PosClient posClient;

    public PosSaleOutboxHandler(PosOrderNotifier notifier, PosClient posClient) {
        this.notifier = notifier;
        this.posClient = posClient;
    }

    @Override
    public OutboxType type() {
        return OutboxType.POS_ORDER;
    }

    @Override
    public OutboxResult handle(OutboxMessage message) {
        Long orderId = message.getOrder().getId();
        Long companyId = message.getCompanyId();

        PosSaleRequest request;
        try {
            request = notifier.prepare(orderId, companyId);
        } catch (RuntimeException e) {
            // Falló antes de que saliera nada hacia el POS: reintentar no puede duplicar.
            return OutboxResult.retryable("No se pudo preparar la venta: " + e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : ": " + e.getMessage()));
        }

        return switch (request.kind()) {
            case SKIP -> OutboxResult.skipped(request.reason());
            case NOT_SENT -> OutboxResult.retryable(request.reason());
            case READY -> send(request, orderId, companyId);
        };
    }

    private OutboxResult send(PosSaleRequest request, Long orderId, Long companyId) {
        PosDelivery delivery = posClient.notifyOrder(request.connection(), request.payload());
        return switch (delivery.outcome()) {
            case SENT -> {
                log.info("Venta registrada en el POS order_id={} company_id={} response={}",
                        orderId, companyId, delivery.response());
                yield OutboxResult.delivered(delivery.response());
            }
            case NOT_SENT -> OutboxResult.retryable(delivery.detail());
            case UNCERTAIN -> OutboxResult.uncertain(delivery.detail());
            case REJECTED -> OutboxResult.permanent(delivery.detail());
        };
    }

    /** La venta ya está entregada y guardada como tal: esto es solo una observación del stock. */
    @Override
    public void afterDelivered(OutboxMessage message, OutboxResult result) {
        notifier.checkStockDivergence(result.response(), message.getCompanyId());
    }
}
