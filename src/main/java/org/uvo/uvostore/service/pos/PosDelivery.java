package org.uvo.uvostore.service.pos;

import java.util.Map;

/**
 * PROD-13b. Qué pasó con una venta enviada al POS, contado de forma que permita decidir si se puede
 * reintentar. Mientras el POS no confirme que respeta la clave de idempotencia (POS-1), la pregunta que
 * importa no es "¿falló?" sino "¿pudo haber llegado?": reintentar algo que llegó emite un segundo
 * documento tributario.
 */
public record PosDelivery(Outcome outcome, String detail, Map<String, Object> response) {

    public enum Outcome {
        /** El POS respondió 2xx: la venta está registrada. */
        SENT,
        /** La petición no salió (o el POS dijo explícitamente que no la procesó): reintentar es seguro. */
        NOT_SENT,
        /** La petición salió y no sabemos qué hizo el POS con ella. */
        UNCERTAIN,
        /** El POS la rechazó por su contenido: reintentar igual no va a cambiar nada. */
        REJECTED
    }

    public static PosDelivery sent(Map<String, Object> response) {
        return new PosDelivery(Outcome.SENT, null, response);
    }

    public static PosDelivery notSent(String detail) {
        return new PosDelivery(Outcome.NOT_SENT, detail, null);
    }

    public static PosDelivery uncertain(String detail) {
        return new PosDelivery(Outcome.UNCERTAIN, detail, null);
    }

    public static PosDelivery rejected(String detail) {
        return new PosDelivery(Outcome.REJECTED, detail, null);
    }
}
