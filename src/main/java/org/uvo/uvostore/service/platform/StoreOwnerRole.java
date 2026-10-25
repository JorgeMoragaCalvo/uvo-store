package org.uvo.uvostore.service.platform;

import org.springframework.stereotype.Component;
import org.uvo.uvostore.entity.security.Role;
import org.uvo.uvostore.entity.security.User;
import org.uvo.uvostore.entity.tenant.Store;
import org.uvo.uvostore.repository.PermissionRepository;
import org.uvo.uvostore.repository.RoleRepository;

import java.util.HashSet;

/**
 * F08. El rol de dueño de una tienda, con el catálogo de permisos entero.
 *
 * <p>Sin esto el alta entregaba un administrador <b>sin ningún rol</b>, y como
 * {@code AuthController.adminAuthorities} compone el token con {@code ROLE_ADMIN} más los permisos
 * de sus roles, el dueño entraba con una sola autoridad. El síntoma no era ni un 403 claro: el menú
 * del panel se dibuja a partir de esos permisos, así que el cliente veía un panel sin secciones
 * —incluida la de Roles, la única que habría servido para arreglarlo— y la salida era SQL a mano.
 * Funcionaba solo para las tiendas anteriores a {@code V15__seed_permissions.sql}, que les creó
 * este mismo rol por migración.
 *
 * <p><b>Esto es una foto del catálogo en el momento de crearlo.</b> Un permiso nuevo añadido por una
 * migración futura no aparece aquí por sí solo: hay que concederlo también a los roles que ya
 * existen, como hace {@code V19__order_refunds.sql} con {@code orders.refund} (y lo hace de forma
 * genérica, sobre cualquier rol que tenga {@code orders.manage}, así que cubre también los roles
 * que crea esta clase). Mantener ese patrón.
 *
 * <p>PROD-23. Vivía dentro de {@code StoreOnboardingServiceImpl}; sale aquí porque recuperar el acceso del dueño
 * ({@code StoreLifecycleServiceImpl}) también se lo devuelve.
 */
@Component
class StoreOwnerRole {

    /**
     * El mismo nombre que usó {@code V15__seed_permissions.sql} para las tiendas que ya existían
     * entonces, para que una tienda nueva y una antigua se vean igual en la pantalla de Roles.
     */
    static final String NAME = "Administrador";

    /** El {@code guard_name} por defecto de {@code Role}, el que espera {@code UNIQUE(store_id, name, guard_name)}. */
    private static final String GUARD = "web";

    private final RoleRepository roleRepository;
    private final PermissionRepository permissionRepository;

    StoreOwnerRole(RoleRepository roleRepository, PermissionRepository permissionRepository) {
        this.roleRepository = roleRepository;
        this.permissionRepository = permissionRepository;
    }

    /** El rol, recién creado, para el dueño de una tienda recién creada. */
    Role create(Store store) {
        Role role = new Role();
        role.setStore(store);
        role.setName(NAME);
        // guardName se queda en su default "web", que es lo que espera UNIQUE(store_id, name, guard_name).
        role.setPermissions(new HashSet<>(permissionRepository.findAll()));
        return roleRepository.save(role);
    }

    /**
     * PROD-23. Le asigna al dueño el rol de su tienda si no lo tiene, y lo vuelve a crear si alguien lo borró. Si
     * existe, sus permisos no se tocan: los pudo ajustar el propio comerciante.
     *
     * @return si hubo que asignarlo (o crearlo)
     */
    boolean ensureAssigned(User owner, Store store) {
        boolean has = owner.getRoles().stream()
                .anyMatch(role -> NAME.equals(role.getName()) && GUARD.equals(role.getGuardName()));
        if (has) {
            return false;
        }
        Role role = roleRepository.findByStoreId(store.getId()).stream()
                .filter(candidate -> NAME.equals(candidate.getName()) && GUARD.equals(candidate.getGuardName()))
                .findFirst()
                .orElseGet(() -> create(store));
        owner.getRoles().add(role);
        return true;
    }
}
