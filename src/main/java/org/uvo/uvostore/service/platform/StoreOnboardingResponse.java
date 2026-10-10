package org.uvo.uvostore.service.platform;

import java.time.Instant;

/**
 * Lo que el operador necesita para entregarle la tienda al cliente.
 *
 * <p>PROD-04 añade las tres últimas. {@code domainVerifiedAt} dice si el dominio propio ya se comprobó
 * (NULL = no, y entonces los enlaces que salgan usan el subdominio), y las dos URLs son las direcciones
 * concretas que el alta tenía que entregar y no entregaba: el §6.2 del plan de producción pide «se crea
 * propietario con permisos y se entrega una URL concreta del storefront y otra del panel», y hasta ahora
 * el operador tenía que componerlas de memoria a partir del nick.
 */
public record StoreOnboardingResponse(
        Long storeId,
        String storeName,
        String slug,
        String domain,
        Long adminUserId,
        String adminEmail,
        Instant domainVerifiedAt,
        String storefrontUrl,
        String adminUrl
) {
}
