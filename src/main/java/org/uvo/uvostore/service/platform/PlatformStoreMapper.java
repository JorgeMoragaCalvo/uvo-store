package org.uvo.uvostore.service.platform;

import org.springframework.stereotype.Component;
import org.uvo.uvostore.entity.security.User;
import org.uvo.uvostore.entity.tenant.Store;
import org.uvo.uvostore.repository.PosConnectionRepository;
import org.uvo.uvostore.repository.PosConnectionRepository.PosLinkView;
import org.uvo.uvostore.repository.UserRepository;
import org.uvo.uvostore.service.url.StorePublicUrlResolver;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * PROD-23. Arma {@link PlatformStoreDto}. Por lote: los dueños y las conexiones de una página entera salen en dos
 * consultas, no en dos por tienda.
 */
@Component
class PlatformStoreMapper {

    private final UserRepository userRepository;
    private final PosConnectionRepository posConnectionRepository;
    private final StorePublicUrlResolver publicUrls;

    PlatformStoreMapper(UserRepository userRepository, PosConnectionRepository posConnectionRepository,
                        StorePublicUrlResolver publicUrls) {
        this.userRepository = userRepository;
        this.posConnectionRepository = posConnectionRepository;
        this.publicUrls = publicUrls;
    }

    PlatformStoreDto toDto(Store store) {
        return toDtos(List.of(store)).getFirst();
    }

    List<PlatformStoreDto> toDtos(List<Store> stores) {
        if (stores.isEmpty()) {
            return List.of();
        }
        List<Long> ownerIds = stores.stream().map(Store::getOwnerUserId).filter(Objects::nonNull).toList();
        Map<Long, User> owners = userRepository.findAllById(ownerIds).stream()
                .collect(Collectors.toMap(User::getId, Function.identity()));
        // Sin cargar las conexiones: cargarlas descifra sus secretos (ver PosConnectionRepository.findLinksByStoreIdIn).
        Map<Long, List<PosLinkView>> connections = posConnectionRepository
                .findLinksByStoreIdIn(stores.stream().map(Store::getId).toList()).stream()
                .collect(Collectors.groupingBy(PosLinkView::getStoreId));
        return stores.stream()
                .map(store -> toDto(store, owners.get(store.getOwnerUserId()),
                        connections.getOrDefault(store.getId(), List.of())))
                .toList();
    }

    private PlatformStoreDto toDto(Store store, User owner, List<PosLinkView> connections) {
        // Un dueño de otra tienda no debería existir (owner_user_id lo escribe solo el alta), pero si existiera no se
        // muestra: la lista no puede ser la forma de ver un usuario de otra tienda.
        User safeOwner = owner != null && owner.getStore().getId().equals(store.getId()) ? owner : null;
        return new PlatformStoreDto(
                store.getId(), store.getName(), store.getSlug(), store.getDomain(), store.getDomainVerifiedAt(),
                store.getStatus(), store.getStatusChangedAt(), store.getCreatedAt(),
                publicUrls.storefrontOrigin(store),
                safeOwner == null ? null : new PlatformStoreDto.Owner(
                        safeOwner.getId(), safeOwner.getName(), safeOwner.getEmail(), safeOwner.isActive(),
                        // La misma regla que UserQueryServiceImpl (PROD-06): hay token y nadie lo usó.
                        safeOwner.getInvitationToken() != null && safeOwner.getInvitationAcceptedAt() == null,
                        safeOwner.getInvitationSentAt()),
                connections.stream()
                        .map(c -> new PlatformStoreDto.PosLink(c.getCompanyId(), c.getCompanyName(),
                                Boolean.TRUE.equals(c.getActive())))
                        .toList());
    }
}
