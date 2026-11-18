package org.uvo.uvostore.entity.outbox.enums;

import java.util.Arrays;
import java.util.List;

/** PROD-13b. Los efectos posteriores al commit de una orden (y, desde IMP-06, los correos de acceso), cada uno con su canal de despacho. */
public enum OutboxType {

    /** Registrar la venta en el POS, una fila por empresa. Emite un documento tributario. */
    POS_ORDER(OutboxChannel.POS),
    /** "Recibimos tu pedido", al crear la orden. */
    ORDER_PLACED_EMAIL(OutboxChannel.MAIL),
    /** "Gracias por tu compra", al confirmar el pago. */
    ORDER_CONFIRMATION_EMAIL(OutboxChannel.MAIL),
    /** La invitación a crear cuenta de un comprador invitado (F24, apagada con PROD-07). */
    CUSTOMER_INVITATION_EMAIL(OutboxChannel.MAIL),
    /**
     * IMP-06. La invitación de un administrador a elegir su contraseña: al darlo de alta, al reenviarla, al crear la
     * tienda (el dueño) y en la recuperación de acceso del dueño.
     */
    ADMIN_INVITATION_EMAIL(OutboxChannel.ACCESS),
    /** IMP-06. El enlace de «¿Olvidaste tu contraseña?» del panel. */
    PASSWORD_RESET_EMAIL(OutboxChannel.ACCESS);

    private final OutboxChannel channel;

    OutboxType(OutboxChannel channel) {
        this.channel = channel;
    }

    public OutboxChannel channel() {
        return channel;
    }

    public static List<OutboxType> of(OutboxChannel channel) {
        return Arrays.stream(values()).filter(type -> type.channel == channel).toList();
    }
}
