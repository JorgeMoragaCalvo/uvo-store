package org.uvo.uvostore.service.outbox;

import io.sentry.Sentry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.uvo.uvostore.config.LogContext;
import org.uvo.uvostore.entity.outbox.OutboxMessage;
import org.uvo.uvostore.entity.outbox.enums.OutboxChannel;
import org.uvo.uvostore.entity.outbox.enums.OutboxStatus;
import org.uvo.uvostore.entity.outbox.enums.OutboxType;
import org.uvo.uvostore.repository.OutboxMessageRepository;
import org.uvo.uvostore.security.TenantContext;
import org.uvo.uvostore.service.AdvisoryLock;
import org.uvo.uvostore.service.metrics.BusinessMetrics;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * PROD-13b. Entrega las filas del outbox: la venta al POS y los correos de una orden.
 *
 * <p>Sustituye a los oyentes {@code AFTER_COMMIT} que corrían en ejecutores en memoria, donde una caída o un
 * redespliegue perdía lo que estuviera en cola, y al reintento del POS, que solo veía las órdenes que ya
 * habían fallado una vez. Lo que se escribió con el commit se entrega aunque el proceso haya muerto en medio.
 *
 * <p><b>Lo que decide qué se reintenta es si el efecto pudo haber llegado.</b> El POS emite un documento
 * tributario por cada venta y todavía no ha confirmado que respete la clave de idempotencia (POS-1), así que
 * una venta de resultado incierto —la petición salió y no hubo respuesta, o el proceso murió durante la
 * llamada— no se reenvía: queda {@code FAILED} para que una persona lo compruebe en el POS (ver V25), salvo
 * con {@code app.pos.retry.enabled=true}. Lo que seguro no salió (no hubo conexión) sí se reintenta, con
 * espera creciente, hasta {@code app.outbox.give-up-after-hours}. Un correo duplicado es una molestia y no un
 * documento, así que en el correo lo incierto se reintenta.
 *
 * <p>La programación vive aparte, en {@link OutboxScheduler}, para que los tests puedan apagarla y llamar a
 * esto directamente.
 */
@Component
public class OutboxDispatcher {

    private static final Logger log = LoggerFactory.getLogger(OutboxDispatcher.class);

    /** Las siguientes a las de la conciliación (…001) y el barrido de stock (…002). */
    static final long POS_LOCK_KEY = 7_312_025_003L;
    static final long MAIL_LOCK_KEY = 7_312_025_004L;

    private static final long MAX_BACKOFF_MINUTES = 30;

    private final OutboxMessageRepository repository;
    private final OutboxStore store;
    private final AdvisoryLock advisoryLock;
    private final Map<OutboxType, OutboxHandler> handlers;
    private final boolean posRetryEnabled;
    private final int batchSize;
    private final Duration giveUpAfter;
    private final Duration inFlightTimeout;
    private final BusinessMetrics metrics;

    /** Se enciende al cerrar el contexto: a partir de ahí no se reclama ninguna fila nueva. */
    private volatile boolean stopping;

    public OutboxDispatcher(OutboxMessageRepository repository, OutboxStore store, AdvisoryLock advisoryLock,
                            List<OutboxHandler> handlers,
                            @Value("${app.pos.retry.enabled:false}") boolean posRetryEnabled,
                            @Value("${app.outbox.batch-size:50}") int batchSize,
                            @Value("${app.outbox.give-up-after-hours:48}") int giveUpAfterHours,
                            @Value("${app.outbox.in-flight-timeout-seconds:120}") int inFlightTimeoutSeconds,
                            BusinessMetrics metrics) {
        this.metrics = metrics;
        this.repository = repository;
        this.store = store;
        this.advisoryLock = advisoryLock;
        // toMap lanza si dos handlers dicen ser del mismo tipo: mejor no arrancar que entregar dos veces.
        this.handlers = handlers.stream().collect(Collectors.toMap(OutboxHandler::type, Function.identity()));
        this.posRetryEnabled = posRetryEnabled;
        this.batchSize = batchSize;
        this.giveUpAfter = Duration.ofHours(giveUpAfterHours);
        this.inFlightTimeout = Duration.ofSeconds(inFlightTimeoutSeconds);
    }

    /**
     * Una corrida del canal. Cada canal tiene su cerrojo consultivo, así que dos instancias no lo despachan
     * a la vez, y el POS y el correo no se esperan entre sí.
     */
    public void dispatch(OutboxChannel channel) {
        if (stopping) {
            return;
        }
        advisoryLock.runExclusively(channel == OutboxChannel.POS ? POS_LOCK_KEY : MAIL_LOCK_KEY,
                "outbox " + channel, () -> runOnce(channel));
    }

    /**
     * Un redespliegue no debe cortar una venta a medio enviar: desde aquí no se reclama nada nuevo, y lo que
     * esté en vuelo termina dentro del plazo de apagado (ver {@code spring.task.scheduling.shutdown.*}). Lo
     * que no llegue a terminar queda en vuelo y la próxima corrida lo verá como incierto.
     */
    @EventListener(ContextClosedEvent.class)
    public void onContextClosed() {
        stopping = true;
    }

    private void runOnce(OutboxChannel channel) {
        List<OutboxType> types = OutboxType.of(channel);
        Instant now = Instant.now();

        for (OutboxMessage orphan : repository.findOrphans(types, now.minus(inFlightTimeout), PageRequest.of(0, batchSize))) {
            recoverOrphan(orphan);
        }

        List<OutboxMessage> due = repository.findDue(types, now, PageRequest.of(0, batchSize));
        // Un POS que no responde no puede quemar la tanda entera ni retener a las demás empresas: tras su
        // primer fallo de red, el resto de sus filas espera a la próxima corrida sin gastar intentos.
        Set<Long> unreachableCompanies = new HashSet<>();
        for (OutboxMessage message : due) {
            if (stopping) {
                log.info("Cierre en curso: el outbox {} deja el resto de la tanda para la próxima vez", channel);
                return;
            }
            if (message.getCompanyId() != null && unreachableCompanies.contains(message.getCompanyId())) {
                continue;
            }
            try {
                OutboxResult.Outcome outcome = deliver(message);
                if (message.getCompanyId() != null
                        && (outcome == OutboxResult.Outcome.RETRYABLE || outcome == OutboxResult.Outcome.UNCERTAIN)) {
                    unreachableCompanies.add(message.getCompanyId());
                }
            } catch (Exception e) {
                // Una fila que revienta no puede llevarse por delante a las demás de la tanda.
                log.error("Fallo despachando la fila del outbox id={} error={}", message.getId(), e.getMessage(), e);
                Sentry.captureException(e);
            }
        }
    }

    /** @return el resultado del intento, o null si la fila no se llegó a intentar. */
    private OutboxResult.Outcome deliver(OutboxMessage message) {
        OutboxHandler handler = handlers.get(message.getType());
        if (handler == null) {
            log.error("Sin handler para las filas {} del outbox (id={}); se dejan pendientes", message.getType(), message.getId());
            return null;
        }
        if (!store.claim(message.getId(), Instant.now())) {
            // Otro proceso se la quedó entre la consulta y el reclamo.
            return null;
        }
        int attempts = message.getAttempts() + 1;

        OutboxResult result;
        try {
            result = handleWithin(handler, message);
        } catch (Exception e) {
            // No se sabe en qué punto falló, así que tampoco si llegó a salir algo.
            log.error("El handler {} lanzó en vez de clasificar el fallo id={} error={}",
                    message.getType(), message.getId(), e.getMessage(), e);
            result = OutboxResult.uncertain("Error inesperado: " + e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : ": " + e.getMessage()));
        }
        if (result == null) {
            result = OutboxResult.uncertain("El handler no devolvió resultado");
        }

        apply(message, attempts, result);

        if (result.outcome() == OutboxResult.Outcome.DELIVERED) {
            OutboxResult delivered = result;
            try {
                withRequestIdOf(message, () -> TenantContext.runWithin(message.getOrder().getStore(),
                        () -> handler.afterDelivered(message, delivered)));
            } catch (Exception e) {
                log.error("Fallo después de entregar la fila del outbox id={} error={}", message.getId(), e.getMessage());
                Sentry.captureException(e);
            }
        }
        return result.outcome();
    }

    private OutboxResult handleWithin(OutboxHandler handler, OutboxMessage message) {
        // Cada fila con el tenant de SU tienda: un job no tiene petición, así que nadie lo ha puesto por él.
        AtomicReference<OutboxResult> result = new AtomicReference<>();
        withRequestIdOf(message, () ->
                TenantContext.runWithin(message.getOrder().getStore(), () -> result.set(handler.handle(message))));
        return result.get();
    }

    /**
     * PROD-15b. Durante la entrega, el id de la petición que encoló la fila: lo llevan las líneas de log de la
     * entrega y {@code PosClient} lo manda al POS. Al salir vuelve el de la pasada del despacho. Una fila anterior
     * a V30 no tiene id y se entrega con el de la pasada.
     */
    private static void withRequestIdOf(OutboxMessage message, Runnable action) {
        String previous = LogContext.requestId();
        if (message.getRequestId() != null) {
            LogContext.putRequestId(message.getRequestId());
        }
        try {
            action.run();
        } finally {
            LogContext.putRequestId(previous);
        }
    }

    /**
     * Una fila que se quedó en vuelo más de lo que puede durar una llamada: el proceso que la reclamó murió
     * durante la entrega, así que no se sabe si llegó.
     */
    private void recoverOrphan(OutboxMessage orphan) {
        log.warn("Fila del outbox huérfana en vuelo id={} tipo={} order_id={}",
                orphan.getId(), orphan.getType(), orphan.getOrder().getId());
        apply(orphan, orphan.getAttempts(), OutboxResult.uncertain(
                "Huérfana en vuelo: el proceso se interrumpió durante la entrega, que pudo haber ocurrido"));
    }

    private void apply(OutboxMessage message, int attempts, OutboxResult result) {
        // PROD-15b. Lo que dijo el intento, no en qué estado quedó la fila: eso lo cuenta el gauge
        // uvostore_outbox_failed (OperationalBacklogMetrics). Una huérfana cuenta como incierta.
        metrics.outboxDelivery(message.getType().channel(), result.outcome());
        Instant now = Instant.now();
        switch (result.outcome()) {
            case DELIVERED -> close(message, attempts, OutboxStatus.SENT, null, now);
            case SKIPPED -> close(message, attempts, OutboxStatus.SKIPPED, result.detail(), now);
            case PERMANENT -> fail(message, attempts, "Rechazado: " + result.detail(), now);
            case UNCERTAIN -> {
                if (message.getType().channel() == OutboxChannel.POS && !posRetryEnabled) {
                    fail(message, attempts, "Resultado incierto, la venta pudo haber llegado al POS: "
                            + result.detail(), now);
                } else {
                    retryLater(message, attempts, result.detail(), now);
                }
            }
            case RETRYABLE -> retryLater(message, attempts, result.detail(), now);
        }
    }

    private void retryLater(OutboxMessage message, int attempts, String detail, Instant now) {
        if (message.getCreatedAt() != null && message.getCreatedAt().plus(giveUpAfter).isBefore(now)) {
            fail(message, attempts, "Sin éxito tras " + giveUpAfter.toHours() + " h: " + detail, now);
            return;
        }
        Instant next = now.plus(backoff(attempts));
        if (!store.reschedule(message.getId(), attempts, detail, next, now)) {
            log.warn("La fila del outbox id={} cambió durante la entrega; no se reprograma", message.getId());
            return;
        }
        log.warn("Entrega del outbox fallida, se reintenta a las {} id={} tipo={} intento={} error={}",
                next, message.getId(), message.getType(), attempts, detail);
    }

    private void close(OutboxMessage message, int attempts, OutboxStatus status, String detail, Instant now) {
        if (!store.finish(message.getId(), attempts, status, detail, now)) {
            log.warn("La fila del outbox id={} cambió durante la entrega; no se marca {}", message.getId(), status);
            return;
        }
        log.info("Outbox {} id={} tipo={} order_id={}{}", status, message.getId(), message.getType(),
                message.getOrder().getId(), detail == null ? "" : " motivo=" + detail);
    }

    /** Fallo definitivo: lo revisa una persona, así que tiene que verse. */
    private void fail(OutboxMessage message, int attempts, String detail, Instant now) {
        if (!store.finish(message.getId(), attempts, OutboxStatus.FAILED, detail, now)) {
            log.warn("La fila del outbox id={} cambió durante la entrega; no se marca FAILED", message.getId());
            return;
        }
        String orderNumber = message.getOrder().getOrderNumber();
        log.error("Outbox: entrega fallida de forma definitiva id={} tipo={} order_number={} company_id={} error={}",
                message.getId(), message.getType(), orderNumber, message.getCompanyId(), detail);
        // Mismo formato que el resto de avisos del proyecto: el número de orden al final, que es lo que se
        // busca en el panel. Un fallo definitivo ocurre una sola vez por fila, así que no inunda.
        Sentry.captureMessage("Outbox: entrega " + message.getType() + " fallida de forma definitiva — "
                + detail + " [order_number=" + orderNumber + "]");
    }

    /** 1, 2, 4, 8, 16 y luego 30 minutos. */
    static Duration backoff(int attempts) {
        int exponent = Math.max(0, Math.min(attempts - 1, 5));
        return Duration.ofMinutes(Math.min(1L << exponent, MAX_BACKOFF_MINUTES));
    }
}
