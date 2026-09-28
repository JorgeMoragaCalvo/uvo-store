package org.uvo.uvostore.entity.order.enums;

/**
 * F12. En qué punto está un reembolso respecto del dinero de verdad.
 *
 * <p>Existe porque la fila se escribe <b>antes</b> de llamar a la pasarela: hasta entonces no había
 * registro alguno hasta que el dinero ya se había movido, así que una caída entre ambas cosas dejaba el
 * dinero fuera y la base diciendo que no se había devuelto nada.
 */
public enum RefundStatus {

    /**
     * Se va a pedir, o se pidió y no sabemos cómo acabó. <b>Cuenta contra el saldo por devolver</b>: ese
     * dinero puede haber salido ya, así que no se vuelve a ofrecer hasta que alguien lo aclare.
     */
    PENDING,

    /** La pasarela devolvió el dinero y quedó registrado con su referencia. */
    COMPLETED,

    /**
     * La pasarela lo rechazó. No cuenta contra el saldo —no se movió un peso— pero la fila se conserva
     * como rastro del intento.
     */
    FAILED
}
