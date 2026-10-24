package org.uvo.uvostore.service.platform;

/**
 * POS-6. El alta creó una tienda y su dueño quedó invitado: falta mandarle el correo, después del commit.
 *
 * <p>Lleva el id y no la entidad: el oyente lo carga en su propia transacción, porque la del alta ya terminó.
 */
public record StoreOwnerInvitedEvent(Long userId) {
}
