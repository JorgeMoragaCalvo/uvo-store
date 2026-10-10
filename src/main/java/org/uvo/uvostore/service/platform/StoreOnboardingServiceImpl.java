package org.uvo.uvostore.service.platform;

import org.uvo.uvostore.service.BusinessException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.uvo.uvostore.entity.security.Role;
import org.uvo.uvostore.entity.security.User;
import org.uvo.uvostore.entity.tenant.Store;
import org.uvo.uvostore.entity.tenant.enums.StoreStatus;
import org.uvo.uvostore.repository.PermissionRepository;
import org.uvo.uvostore.repository.RoleRepository;
import org.uvo.uvostore.repository.StoreRepository;
import org.uvo.uvostore.repository.UserRepository;
import org.uvo.uvostore.service.url.StorePublicUrlResolver;

import java.time.Instant;
import java.util.HashSet;
import java.util.NoSuchElementException;
import java.util.Set;

// Store onboarding is deliberately NOT self-service — see PlatformApiKeyAuthFilter. The operator
// team collects the client's nick/domain/admin credentials through whatever channel they use, and
// enters them here once; from that point on the client manages everything themselves through the
// normal admin panel (already fully built) under their own slug/domain.
@Service
public class StoreOnboardingServiceImpl implements StoreOnboardingService {

    /**
     * F08. El mismo nombre que usó {@code V15__seed_permissions.sql} para las tiendas que ya existían
     * entonces, para que una tienda nueva y una antigua se vean igual en la pantalla de Roles.
     */
    private static final String OWNER_ROLE_NAME = "Administrador";

    private final StoreRepository storeRepository;
    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final PermissionRepository permissionRepository;
    private final PasswordEncoder passwordEncoder;
    private final StorePublicUrlResolver publicUrls;

    public StoreOnboardingServiceImpl(StoreRepository storeRepository, UserRepository userRepository,
                                      RoleRepository roleRepository, PermissionRepository permissionRepository,
                                      PasswordEncoder passwordEncoder, StorePublicUrlResolver publicUrls) {
        this.storeRepository = storeRepository;
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.permissionRepository = permissionRepository;
        this.passwordEncoder = passwordEncoder;
        this.publicUrls = publicUrls;
    }

    @Override
    @Transactional
    public StoreOnboardingResponse createStore(StoreOnboardingCommand command) {
        String slug = command.slug().toLowerCase().trim();
        if (storeRepository.existsBySlug(slug)) {
            throw new BusinessException("Ese nick ya está en uso");
        }
        String domain = normalizeDomain(command.domain());
        if (domain != null && storeRepository.existsByDomain(domain)) {
            throw new BusinessException("Ese dominio ya está en uso");
        }

        Store store = new Store();
        store.setName(command.storeName());
        store.setSlug(slug);
        store.setDomain(domain);
        store.setStatus(StoreStatus.ACTIVE);
        Store savedStore = storeRepository.save(store);

        User admin = new User();
        admin.setStore(savedStore);
        admin.setName(command.adminName());
        admin.setEmail(command.adminEmail());
        admin.setPassword(passwordEncoder.encode(command.adminPassword()));
        admin.setActive(true);
        admin.setAdmin(true);
        admin.setRoles(new HashSet<>(Set.of(ownerRole(savedStore))));
        User savedAdmin = userRepository.save(admin);

        savedStore.setOwnerUserId(savedAdmin.getId());
        storeRepository.save(savedStore);

        return toResponse(savedStore, savedAdmin);
    }

    @Override
    @Transactional
    public StoreOnboardingResponse updateDomain(Long storeId, String domain) {
        Store store = storeRepository.findById(storeId)
                .orElseThrow(() -> new NoSuchElementException("Store " + storeId + " not found"));

        String normalized = normalizeDomain(domain);
        if (normalized != null && !normalized.equals(store.getDomain()) && storeRepository.existsByDomain(normalized)) {
            throw new BusinessException("Ese dominio ya está en uso");
        }

        // PROD-04. Un dominio distinto es un dominio sin comprobar: si la verificación se heredara, el
        // primer correo saldría con un enlace a un dominio cuyo DNS puede no apuntar aquí todavía. Volver
        // al subdominio de plataforma mientras tanto es el estado seguro, y funciona siempre.
        if (!java.util.Objects.equals(normalized, store.getDomain())) {
            store.setDomainVerifiedAt(null);
        }
        store.setDomain(normalized);
        Store saved = storeRepository.save(store);
        User admin = saved.getOwnerUserId() == null ? null : userRepository.findById(saved.getOwnerUserId()).orElse(null);
        return toResponse(saved, admin);
    }

    /**
     * PROD-04. Deja constancia de que el dominio propio de la tienda ya apunta aquí, que es lo que habilita
     * su uso en los enlaces que salen (correos, retornos de pasarela, webhooks).
     *
     * <p><b>No sondea DNS ni pide el certificado</b>: registra la comprobación que hizo el operador. Hacerlo
     * desde aquí exigiría salir a la red desde la petición y aún así no diría gran cosa —la resolución
     * depende de desde dónde se pregunte, y el proxy inverso todavía no existe (PROD-03)—. Automatizarlo
     * cuando haya staging es una mejora, no un requisito: lo que hacía falta era que existiera el estado.
     *
     * @param verified false deshace la verificación, para cuando un dominio deja de funcionar y hay que
     *        devolver los enlaces al subdominio sin perder el dominio configurado.
     */
    @Override
    @Transactional
    public StoreOnboardingResponse setDomainVerified(Long storeId, boolean verified) {
        Store store = storeRepository.findById(storeId)
                .orElseThrow(() -> new NoSuchElementException("Store " + storeId + " not found"));
        if (verified && (store.getDomain() == null || store.getDomain().isBlank())) {
            throw new BusinessException("Esta tienda no tiene dominio propio que verificar");
        }
        store.setDomainVerifiedAt(verified ? Instant.now() : null);
        Store saved = storeRepository.save(store);
        User admin = saved.getOwnerUserId() == null ? null : userRepository.findById(saved.getOwnerUserId()).orElse(null);
        return toResponse(saved, admin);
    }

    /**
     * F08. El rol de dueño de la tienda, con el catálogo de permisos entero.
     *
     * <p>Sin esto el alta entregaba un administrador <b>sin ningún rol</b>, y como
     * {@code AuthController.adminAuthorities} compone el token con {@code ROLE_ADMIN} más los permisos
     * de sus roles, el dueño entraba con una sola autoridad. El síntoma no era ni un 403 claro: el menú
     * del panel se dibuja a partir de esos permisos, así que el cliente veía un panel sin secciones
     * —incluida la de Roles, la única que habría servido para arreglarlo— y la salida era SQL a mano.
     * Funcionaba solo para las tiendas anteriores a {@code V15__seed_permissions.sql}, que les creó
     * este mismo rol por migración.
     *
     * <p><b>Esto es una foto del catálogo en el momento del alta.</b> Un permiso nuevo añadido por una
     * migración futura no aparece aquí por sí solo: hay que concederlo también a los roles que ya
     * existen, como hace {@code V19__order_refunds.sql} con {@code orders.refund} (y lo hace de forma
     * genérica, sobre cualquier rol que tenga {@code orders.manage}, así que cubre también los roles
     * que crea este método). Mantener ese patrón.
     */
    private Role ownerRole(Store store) {
        Role role = new Role();
        role.setStore(store);
        role.setName(OWNER_ROLE_NAME);
        // guardName se queda en su default "web", que es lo que espera UNIQUE(store_id, name, guard_name).
        role.setPermissions(new HashSet<>(permissionRepository.findAll()));
        return roleRepository.save(role);
    }

    private String normalizeDomain(String domain) {
        return domain == null || domain.isBlank() ? null : domain.toLowerCase().trim();
    }

    private StoreOnboardingResponse toResponse(Store store, User admin) {
        return new StoreOnboardingResponse(
                store.getId(), store.getName(), store.getSlug(), store.getDomain(),
                admin == null ? null : admin.getId(), admin == null ? null : admin.getEmail(),
                store.getDomainVerifiedAt(),
                publicUrls.storefrontOrigin(store),
                publicUrls.storefrontUrl(store, "/admin/login")
        );
    }
}
