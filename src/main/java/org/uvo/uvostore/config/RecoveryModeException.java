package org.uvo.uvostore.config;

/**
 * IMP-09. Un efecto externo que se intentó con el modo recuperación encendido ({@link RecoveryModePolicy}). El manejador
 * global la responde 503 {@code RECOVERY_MODE}; en un trabajo o en el outbox no debería llegar a verse, porque con el
 * modo encendido no corren.
 */
public class RecoveryModeException extends RuntimeException {

    public static final String CODE = "RECOVERY_MODE";

    public RecoveryModeException(String what) {
        super("Modo recuperación: " + what + " está detenido hasta terminar la conciliación");
    }
}
