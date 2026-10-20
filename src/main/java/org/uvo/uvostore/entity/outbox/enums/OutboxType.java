package org.uvo.uvostore.entity.outbox.enums;

import java.util.Arrays;
import java.util.List;

/** PROD-13b. Los efectos posteriores al commit de una orden, cada uno con su canal de despacho. */
public enum OutboxType {

    /** Registrar la venta en el POS, una fila por empresa. Emite un documento tributario. */
    POS_ORDER(OutboxChannel.POS),
    /** "Recibimos tu pedido", al crear la orden. */
    ORDER_PLACED_EMAIL(OutboxChannel.MAIL),
    /** "Gracias por tu compra", al confirmar el pago. */
    ORDER_CONFIRMATION_EMAIL(OutboxChannel.MAIL),
    /** La invitación a crear cuenta de un comprador invitado (F24, apagada con PROD-07). */
    CUSTOMER_INVITATION_EMAIL(OutboxChannel.MAIL);

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
