package org.uvo.uvostore.service.outbox;

import org.uvo.uvostore.entity.outbox.OutboxMessage;
import org.uvo.uvostore.entity.outbox.enums.OutboxType;

/**
 * PROD-13b. Quien sabe entregar un tipo de fila del outbox.
 *
 * <p>El despachador la llama <b>fuera de toda transacción</b> y con el tenant de la orden ya fijado. Lo que
 * necesite leer de la base, que lo lea en una transacción corta propia y devuelva valores planos: la llamada
 * de red no debe tener una conexión de Hikari tomada mientras espera.
 *
 * <p>No debería lanzar: los fallos se devuelven clasificados. Si lanza igualmente, el despachador lo trata
 * como {@link OutboxResult.Outcome#UNCERTAIN}, que es lo prudente cuando no se sabe en qué punto falló.
 */
public interface OutboxHandler {

    OutboxType type();

    OutboxResult handle(OutboxMessage message);

    /**
     * Lo que haya que hacer después de dar la fila por entregada (y ya guardada como tal). Un fallo aquí se
     * registra y no cambia el estado de la fila.
     */
    default void afterDelivered(OutboxMessage message, OutboxResult result) {
    }

    /**
     * IMP-08. Qué hacer con una fila huérfana en vuelo: el proceso que la reclamó murió durante la entrega. Por defecto
     * sigue incierta; el POS consulta la venta antes de decidir (contrato §10). Se llama como {@link #handle}: fuera de
     * toda transacción y con el tenant de la fila.
     */
    default OutboxResult resolveUncertain(OutboxMessage message, String detail) {
        return OutboxResult.uncertain(detail);
    }
}
