package org.uvo.uvostore.service.pos;

import java.time.Duration;
import java.util.Map;

/**
 * PROD-13b e IMP-08. Qué pasó con una venta enviada al POS, clasificado como pide el contrato (§10) para decidir si se
 * puede reintentar. La pregunta que importa no es "¿falló?" sino "¿pudo haber llegado?".
 *
 * @param errorCode  el {@code error.code} del POS, si respondió con uno (§11)
 * @param retryAfter lo que pidió el POS en {@code Retry-After}, si lo pidió
 */
public record PosDelivery(Outcome outcome, String detail, Map<String, Object> response, String errorCode,
                          Duration retryAfter) {

    public enum Outcome {
        /** El POS respondió 2xx: la venta está registrada (201 nueva, 200 repetida). */
        SENT,
        /** La petición no salió, o el POS dijo explícitamente que no la procesó: reintentar es seguro. */
        NOT_SENT,
        /** La petición salió y no sabemos qué hizo el POS con ella: hay que consultar (§9). */
        UNCERTAIN,
        /** El POS la rechazó: reintentar igual no va a cambiar nada. */
        REJECTED
    }

    public static PosDelivery sent(Map<String, Object> response) {
        return new PosDelivery(Outcome.SENT, null, response, null, null);
    }

    public static PosDelivery notSent(String detail) {
        return new PosDelivery(Outcome.NOT_SENT, detail, null, null, null);
    }

    public static PosDelivery notSent(String detail, String errorCode, Duration retryAfter) {
        return new PosDelivery(Outcome.NOT_SENT, detail, null, errorCode, retryAfter);
    }

    public static PosDelivery uncertain(String detail) {
        return new PosDelivery(Outcome.UNCERTAIN, detail, null, null, null);
    }

    public static PosDelivery rejected(String detail) {
        return new PosDelivery(Outcome.REJECTED, detail, null, null, null);
    }

    public static PosDelivery rejected(String detail, String errorCode) {
        return new PosDelivery(Outcome.REJECTED, detail, null, errorCode, null);
    }
}
