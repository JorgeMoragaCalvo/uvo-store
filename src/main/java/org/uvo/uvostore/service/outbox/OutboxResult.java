package org.uvo.uvostore.service.outbox;

import java.util.Map;

/**
 * PROD-13b. Cómo terminó un intento de entrega, en los términos que necesita el despachador para decidir
 * qué hacer con la fila.
 *
 * @param response lo que respondió el destino, si sirve para algo después de entregar (el POS devuelve su
 *                 stock); puede ser null
 */
public record OutboxResult(Outcome outcome, String detail, Map<String, Object> response) {

    public enum Outcome {
        /** Entregado. */
        DELIVERED,
        /** No había nada que entregar, o dejó de tener sentido. */
        SKIPPED,
        /** No se entregó y es seguro volver a intentarlo: no salió nada. */
        RETRYABLE,
        /** No se sabe si llegó. Volver a intentarlo puede duplicar. */
        UNCERTAIN,
        /** El destino lo rechazó por su contenido: reintentar no cambia nada. */
        PERMANENT
    }

    public static OutboxResult delivered(Map<String, Object> response) {
        return new OutboxResult(Outcome.DELIVERED, null, response);
    }

    public static OutboxResult skipped(String detail) {
        return new OutboxResult(Outcome.SKIPPED, detail, null);
    }

    public static OutboxResult retryable(String detail) {
        return new OutboxResult(Outcome.RETRYABLE, detail, null);
    }

    public static OutboxResult uncertain(String detail) {
        return new OutboxResult(Outcome.UNCERTAIN, detail, null);
    }

    public static OutboxResult permanent(String detail) {
        return new OutboxResult(Outcome.PERMANENT, detail, null);
    }
}
