package org.uvo.uvostore.entity.order.enums;

/**
 * G4. Qué clase de devolución quedó registrada.
 *
 * <p>{@code EXTERNAL} es el único que no pasó por la pasarela desde aquí: es el reembolso que alguien
 * ya hizo a mano en el panel de Transbank, Stripe o MercadoPago y que solo se registra para que la
 * base deje de contradecir a la pasarela. Distinguirlo importa — un {@code FULL} significa que este
 * sistema movió el dinero y puede responder por ello; un {@code EXTERNAL}, que alguien dice que lo
 * movió.
 */
public enum RefundType {
    FULL,
    PARTIAL,
    EXTERNAL
}
