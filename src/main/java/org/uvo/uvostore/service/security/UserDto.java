package org.uvo.uvostore.service.security;

import java.time.Instant;
import java.util.List;

/**
 * PROD-06 añade las dos últimas, y **nunca el token**: el panel necesita distinguir a un invitado que
 * todavía no ha entrado de un administrador normal —si no, el botón de reenviar no tendría a quién
 * ofrecerse— y necesita la fecha de envío para saber si la invitación ya caducó. El token no, porque
 * eso convertiría cualquier lectura de la lista en una forma de entrar en la cuenta de otro.
 *
 * @param invitationPending hay una invitación viva y nadie la ha aceptado.
 * @param invitationExpiresAt cuándo deja de valer, ya calculado — el panel no vuelve a implementar el
 *        plazo, que lo decide {@code AdminInvitationPolicy}. Mandar solo la fecha de envío obligaría al
 *        frontend a llevar su propia copia del TTL, y a discrepar en silencio el día que cambie.
 */
public record UserDto(
        Long id, String name, String email, String phone, String avatar, boolean active,
        Instant lastLoginAt, String notes, List<RoleRefDto> roles, Instant createdAt,
        boolean invitationPending, Instant invitationExpiresAt
) {
}
