package org.uvo.uvostore.service.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.uvo.uvostore.entity.outbox.OutboxMessage;
import org.uvo.uvostore.entity.outbox.enums.OutboxType;
import org.uvo.uvostore.entity.pos.PosConnection;
import org.uvo.uvostore.repository.PosConnectionRepository;
import org.uvo.uvostore.security.UvoHmac;
import org.uvo.uvostore.service.pos.PosClient;
import org.uvo.uvostore.service.pos.PosDelivery;
import org.uvo.uvostore.service.pos.PosLookup;
import org.uvo.uvostore.service.pos.PosOrderNotifier;
import org.uvo.uvostore.service.pos.PosOrderPayloadMapper;
import org.uvo.uvostore.service.pos.PosSaleRequest;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;

/**
 * PROD-13b. Registra una venta en una empresa del POS: una fila {@code POS_ORDER} del outbox.
 *
 * <p>Ocupa el lugar de {@code PosNotificationListener}. F07 sigue en pie: la fila solo se escribe al
 * confirmar el pago, así que una orden sin cobrar no emite documento. Y PROD-12 también: con la integración
 * apagada no se encola nada, y si se apaga después, {@link PosOrderNotifier#prepare} la omite.
 *
 * <p>IMP-08, con el contrato v1:
 * <ul>
 *   <li>El cuerpo se fija en la fila la primera vez ({@link OutboxStore#fixPayload}) y cada reintento manda esos
 *       mismos bytes: con otro cuerpo, la misma clave sería {@code IDEMPOTENCY_CONFLICT} (§8).</li>
 *   <li>Antes de fijarlo se comprueban las invariantes del §7: si no cuadran, el POS respondería {@code 422} para
 *       siempre, así que se rechaza aquí, sin enviar nada.</li>
 *   <li>Un resultado incierto se consulta antes de decidir (§10, {@link #resolveUncertain}).</li>
 * </ul>
 */
@Component
public class PosSaleOutboxHandler implements OutboxHandler {

    private static final Logger log = LoggerFactory.getLogger(PosSaleOutboxHandler.class);

    private final PosOrderNotifier notifier;
    private final PosClient posClient;
    private final PosOrderPayloadMapper payloadMapper;
    private final OutboxStore store;
    private final PosConnectionRepository connectionRepository;

    public PosSaleOutboxHandler(PosOrderNotifier notifier, PosClient posClient, PosOrderPayloadMapper payloadMapper,
                                OutboxStore store, PosConnectionRepository connectionRepository) {
        this.notifier = notifier;
        this.posClient = posClient;
        this.payloadMapper = payloadMapper;
        this.store = store;
        this.connectionRepository = connectionRepository;
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
            case READY -> send(message, request, orderId, companyId);
        };
    }

    private OutboxResult send(OutboxMessage message, PosSaleRequest request, Long orderId, Long companyId) {
        String json = message.getPayload();
        String key = request.payload().idempotencyKey();
        if (json == null) {
            Optional<String> violation = PosOrderPayloadMapper.invariantViolation(request.payload().body());
            if (violation.isPresent()) {
                return OutboxResult.permanent("VALIDATION (antes de enviar): " + violation.get());
            }
            json = store.fixPayload(message.getId(), payloadMapper.toJson(request.payload()));
            message.setPayload(json);
        } else {
            // Ya salió al menos una vez (un reintento, o un reencolado desde «Envíos fallidos»): antes de volver a
            // mandarla se pregunta si el POS ya la tiene, así una venta que llegó no depende de la idempotencia.
            Optional<OutboxResult> settled = settledByLookup(message, request.connection(), key, json);
            if (settled.isPresent()) {
                return settled.get();
            }
        }

        PosDelivery delivery = posClient.notifyOrder(request.connection(), key, json);
        return switch (delivery.outcome()) {
            case SENT -> {
                log.info("Venta registrada en el POS order_id={} company_id={} sale_id={}",
                        orderId, companyId, saleIdOf(delivery.response()));
                recordSale(message, saleIdOf(delivery.response()));
                yield OutboxResult.delivered(delivery.response());
            }
            case NOT_SENT -> OutboxResult.retryable(delivery.detail(), delivery.retryAfter());
            case UNCERTAIN -> resolve(message, request.connection(), key, json, delivery.detail());
            case REJECTED -> OutboxResult.permanent(delivery.detail());
        };
    }

    /**
     * IMP-08. Una fila huérfana en vuelo (el proceso murió durante la entrega): se consulta al POS antes de decidir.
     * Si el cuerpo nunca se fijó, no salió nada y se puede reintentar.
     */
    @Override
    public OutboxResult resolveUncertain(OutboxMessage message, String detail) {
        if (message.getPayload() == null) {
            return OutboxResult.retryable(detail + " (el cuerpo no llegó a fijarse: no salió nada)");
        }
        Optional<PosConnection> connection = activeConnection(message.getCompanyId());
        if (connection.isEmpty()) {
            return OutboxResult.uncertain(detail + " (sin conexión activa para consultar)");
        }
        String key = PosOrderPayloadMapper.idempotencyKey(message.getOrder().getOrderNumber(), message.getCompanyId());
        return resolve(message, connection.get(), key, message.getPayload(), detail);
    }

    /** §10, la tabla de las inciertas. */
    private OutboxResult resolve(OutboxMessage message, PosConnection connection, String key, String json,
                                 String detail) {
        PosLookup lookup = lookup(connection, key);
        return switch (lookup.status()) {
            case REGISTERED -> resolveRegistered(message, lookup, json, detail);
            case NOT_PROCESSED -> OutboxResult.retryable(detail + " — el POS confirma que no la procesó; se reenvía");
            case IN_PROGRESS -> OutboxResult.retryable(detail + " — el POS la está procesando", lookup.retryAfter());
            case UNKNOWN -> OutboxResult.uncertain(detail + " — consulta: " + lookup.detail());
        };
    }

    /** Antes de reenviar: registrada = entregada, en curso = esperar; lo demás deja seguir con el envío. */
    private Optional<OutboxResult> settledByLookup(OutboxMessage message, PosConnection connection, String key,
                                                   String json) {
        PosLookup lookup = lookup(connection, key);
        if (lookup.status() == PosLookup.Status.REGISTERED) {
            return Optional.of(resolveRegistered(message, lookup, json, "Ya estaba registrada en el POS"));
        }
        if (lookup.status() == PosLookup.Status.IN_PROGRESS) {
            return Optional.of(OutboxResult.retryable("El POS la está procesando", lookup.retryAfter()));
        }
        return Optional.empty();
    }

    private OutboxResult resolveRegistered(OutboxMessage message, PosLookup lookup, String json, String detail) {
        String ours = UvoHmac.sha256Hex(json.getBytes(StandardCharsets.UTF_8));
        if (lookup.bodySha256() != null && !lookup.bodySha256().equalsIgnoreCase(ours)) {
            return OutboxResult.uncertain(detail + " — el POS tiene esta clave registrada con otro cuerpo");
        }
        log.info("La consulta confirma la venta en el POS order_id={} company_id={} sale_id={}",
                message.getOrder().getId(), message.getCompanyId(), lookup.saleId());
        recordSale(message, lookup.saleId());
        return OutboxResult.delivered(null);
    }

    /** {@code PosClient.lookup} no lanza; un nulo (un cliente sustituido que no responde) cuenta como sin respuesta. */
    private PosLookup lookup(PosConnection connection, String key) {
        PosLookup lookup = posClient.lookup(connection, key);
        return lookup != null ? lookup : PosLookup.unknown("sin respuesta a la consulta");
    }

    private Optional<PosConnection> activeConnection(Long companyId) {
        return connectionRepository.findByCompanyId(companyId).filter(PosConnection::isActive);
    }

    private void recordSale(OutboxMessage message, Long saleId) {
        if (saleId != null) {
            store.recordPosSale(message.getId(), saleId);
        }
    }

    private static Long saleIdOf(Map<String, Object> response) {
        Object data = response == null ? null : response.get("data");
        return data instanceof Map<?, ?> fields && fields.get("sale_id") instanceof Number number
                ? number.longValue() : null;
    }

    /** La venta ya está entregada y guardada como tal: esto es solo una observación del stock. */
    @Override
    public void afterDelivered(OutboxMessage message, OutboxResult result) {
        notifier.checkStockDivergence(result.response(), message.getCompanyId());
    }
}
