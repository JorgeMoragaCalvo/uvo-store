package org.uvo.uvostore.entity.outbox.enums;

/** PROD-13b. Ciclo de vida de una fila del outbox. */
public enum OutboxStatus {
    /** Por entregar, a partir de {@code next_attempt_at}. */
    PENDING,
    /**
     * Reclamada y con el intento ya contado, antes de la llamada externa. Una fila que se queda aquí más
     * de lo que puede durar una llamada es huérfana de un proceso que murió en medio, y su resultado es
     * incierto: la llamada pudo haber llegado.
     */
    IN_FLIGHT,
    /** Entregada. */
    SENT,
    /** No había nada que entregar, o dejó de tener sentido (la orden se canceló, el POS se apagó…). */
    SKIPPED,
    /** Fallo definitivo: lo revisa una persona (ver el comentario de V25). */
    FAILED
}
