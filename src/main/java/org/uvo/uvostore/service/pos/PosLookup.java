package org.uvo.uvostore.service.pos;

import java.time.Duration;

/**
 * IMP-08. Lo que contestó el POS a la consulta de una venta ({@code GET /api/v1/orders/external/{clave}}, contrato §9).
 *
 * @param saleId     el {@code sale_id}, solo con {@link Status#REGISTERED}
 * @param bodySha256 el hash del cuerpo que registró el POS, solo con {@link Status#REGISTERED}
 * @param retryAfter lo que pidió el POS en {@code Retry-After}, con {@link Status#IN_PROGRESS}
 */
public record PosLookup(Status status, Long saleId, String bodySha256, Duration retryAfter, String detail) {

    public enum Status {
        /** La venta está registrada: está entregada y no se reenvía. */
        REGISTERED,
        /** El POS <b>garantiza</b> que no la procesó: se puede reenviar con la misma clave. */
        NOT_PROCESSED,
        /** Se está procesando ahora: hay que volver a preguntar. */
        IN_PROGRESS,
        /** Cualquier otra cosa (sin respuesta, un 404 de un proxy, un 5xx): no sirve para decidir. */
        UNKNOWN
    }

    public static PosLookup unknown(String detail) {
        return new PosLookup(Status.UNKNOWN, null, null, null, detail);
    }
}
