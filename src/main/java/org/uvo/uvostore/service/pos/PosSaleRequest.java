package org.uvo.uvostore.service.pos;

import org.uvo.uvostore.entity.pos.PosConnection;

/**
 * PROD-13b. Lo que hace falta para enviar una venta a una empresa del POS, armado en una transacción de
 * lectura corta para que la llamada de red vaya después, fuera de toda transacción. O el motivo por el
 * que no hay que enviarla.
 */
public record PosSaleRequest(Kind kind, String reason, PosConnection connection, PosOrderPayload payload) {

    public enum Kind {
        /** Lista para enviar. */
        READY,
        /** No hay que enviarla: la orden ya no está pagada, el POS está apagado, no lleva productos de esa empresa… */
        SKIP,
        /** Habría que enviarla pero ahora no se puede, y no se ha enviado nada: se reintenta más tarde. */
        NOT_SENT
    }

    public static PosSaleRequest ready(PosConnection connection, PosOrderPayload payload) {
        return new PosSaleRequest(Kind.READY, null, connection, payload);
    }

    public static PosSaleRequest skip(String reason) {
        return new PosSaleRequest(Kind.SKIP, reason, null, null);
    }

    public static PosSaleRequest notSent(String reason) {
        return new PosSaleRequest(Kind.NOT_SENT, reason, null, null);
    }
}
