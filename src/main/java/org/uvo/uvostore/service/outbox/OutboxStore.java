package org.uvo.uvostore.service.outbox;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.uvo.uvostore.entity.outbox.enums.OutboxStatus;
import org.uvo.uvostore.repository.OutboxMessageRepository;

import java.time.Instant;

/**
 * PROD-13b. Cada cambio de estado de una fila del outbox, confirmado en su propia transacción.
 *
 * <p>El que importa es {@link #claim}: el intento queda contado y la fila en {@code IN_FLIGHT}
 * <b>antes</b> de la llamada externa, y confirmado. Si el proceso muere durante la llamada, la fila se queda
 * en vuelo y el despachador sabrá que el resultado es incierto, en vez de creer que no se intentó nunca y
 * volver a enviar a ciegas. Es el mismo razonamiento que {@code RefundIntentStore} (F12) con los reembolsos.
 *
 * <p><b>Bean aparte a propósito</b>, por la misma razón que allí: {@code REQUIRES_NEW} en un método de la
 * misma clase que lo llama no pasa por el proxy de Spring, y la transacción nueva no se abriría nunca.
 */
@Component
public class OutboxStore {

    private final OutboxMessageRepository repository;

    public OutboxStore(OutboxMessageRepository repository) {
        this.repository = repository;
    }

    /** @return true si esta llamada se quedó con la fila; false si ya no estaba pendiente. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean claim(Long id, Instant now) {
        return repository.claim(id, now) == 1;
    }

    /** @return false si la fila cambió entretanto (otro proceso la cerró o la reclamó de nuevo). */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean finish(Long id, int attempts, OutboxStatus status, String error, Instant now) {
        return repository.finish(id, attempts, status, error, now) == 1;
    }

    /**
     * IMP-08. El cuerpo de la venta, fijado antes de que salga por primera vez. Si ya estaba fijado devuelve ese, para
     * que todo reintento mande los mismos bytes.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public String fixPayload(Long id, String payload) {
        if (repository.fixPayload(id, payload) == 1) {
            return payload;
        }
        return repository.findById(id).map(org.uvo.uvostore.entity.outbox.OutboxMessage::getPayload).orElse(payload);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordPosSale(Long id, Long saleId) {
        repository.recordPosSale(id, saleId);
    }

    /** @return false si la fila cambió entretanto. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean reschedule(Long id, int attempts, String error, Instant nextAttemptAt, Instant now) {
        return repository.reschedule(id, attempts, error, nextAttemptAt, now) == 1;
    }
}
