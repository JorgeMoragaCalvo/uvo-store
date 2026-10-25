package org.uvo.uvostore.service.platform;

import org.uvo.uvostore.entity.tenant.enums.StoreStatus;

import java.time.Instant;
import java.util.List;

/**
 * PROD-23. Una tienda tal como la ve la plataforma: la lista, el detalle y la respuesta de cada operación sobre ella.
 *
 * <p><b>Nunca lleva secretos</b>: de la conexión con el POS solo la empresa y si está activa (ni el token, ni el
 * secreto de webhook, ni la URL de su API), y del dueño nada de su contraseña ni de sus tokens.
 *
 * @param statusChangedAt cuándo cambió el estado por última vez; nulo si nunca cambió desde el alta
 * @param owner nulo si la tienda no tiene dueño registrado ({@code stores.owner_user_id})
 */
public record PlatformStoreDto(
        Long id,
        String name,
        String slug,
        String domain,
        Instant domainVerifiedAt,
        StoreStatus status,
        Instant statusChangedAt,
        Instant createdAt,
        String storefrontUrl,
        Owner owner,
        List<PosLink> posConnections
) {

    /** @param invitationPending tiene una invitación sin usar: todavía no eligió su contraseña. */
    public record Owner(Long userId, String name, String email, boolean active, boolean invitationPending,
                        Instant invitationSentAt) {
    }

    public record PosLink(Long companyId, String companyName, boolean active) {
    }
}
