package org.uvo.uvostore.service.payment;

import org.uvo.uvostore.entity.order.enums.RefundStatus;

/**
 * GAP-02. Lo que el operador comprobó en el panel de la pasarela sobre una intención {@code PENDING}: el dinero
 * salió o no salió. No hay una tercera opción a propósito; si todavía no se sabe, la intención se deja como está.
 */
public enum RefundResolution {
    /** El dinero salió: el importe queda devuelto, con la referencia que muestra la pasarela. */
    COMPLETED(RefundStatus.COMPLETED),
    /** No salió: el importe vuelve a quedar disponible. */
    FAILED(RefundStatus.FAILED);

    private final RefundStatus status;

    RefundResolution(RefundStatus status) {
        this.status = status;
    }

    public RefundStatus status() {
        return status;
    }
}
