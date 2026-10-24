package org.uvo.uvostore.service.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.uvo.uvostore.entity.security.Role;
import org.uvo.uvostore.entity.security.User;
import org.uvo.uvostore.entity.tenant.Store;
import org.uvo.uvostore.repository.RoleRepository;
import org.uvo.uvostore.repository.UserRepository;
import org.uvo.uvostore.security.TenantContext;
import org.uvo.uvostore.security.TokenVersionService;
import org.uvo.uvostore.service.BusinessException;
import org.uvo.uvostore.service.catalog.FileStorageService;

import java.time.Duration;
import java.util.HashSet;
import java.util.NoSuchElementException;
import java.util.Set;

@Service
public class UserServiceImpl implements UserService {

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;
    private final FileStorageService fileStorageService;
    private final TokenVersionService tokenVersionService;
    // PROD-06. La vigencia de la invitación y el intervalo entre reenvíos los decide un solo sitio: antes
    // ttl-days se leía aquí y en AuthController por separado.
    private final AdminInvitationPolicy invitationPolicy;
    // POS-6. El token y el correo de la invitación, compartidos con el alta de tiendas.
    private final AdminInvitationMailer invitationMailer;

    public UserServiceImpl(UserRepository userRepository, RoleRepository roleRepository,
                            PasswordEncoder passwordEncoder, FileStorageService fileStorageService,
                            TokenVersionService tokenVersionService, AdminInvitationPolicy invitationPolicy,
                            AdminInvitationMailer invitationMailer) {
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.passwordEncoder = passwordEncoder;
        this.fileStorageService = fileStorageService;
        this.tokenVersionService = tokenVersionService;
        this.invitationPolicy = invitationPolicy;
        this.invitationMailer = invitationMailer;
    }

    @Override
    @Transactional
    public User createUser(UserCreateCommand command) {
        Store store = TenantContext.requireCurrent();
        User user = new User();
        user.setStore(store);
        applyCommonFields(user, command);

        if (command.sendInvitation()) {
            // Invitación: la cuenta nace SIN contraseña y con un token de un solo uso. El administrador
            // existe, tiene roles y permisos, y está inerte hasta que su dueño elija su clave — nadie más
            // llega a conocerla, que es el punto de todo esto.
            //
            // Y entrar es imposible mientras tanto: adminLogin acaba en
            // passwordEncoder.matches(raw, null), y el encoder descarta por longitud antes de comparar
            // (AbstractValidatingPasswordEncoder: `!StringUtils.hasLength(encodedPassword)` → false), así
            // que no hay ventana en la que la cuenta valga sin activarse.
            user.setPassword(null);
            invitationMailer.issue(user);
        } else {
            // Sin invitación hace falta contraseña, y se exige AQUÍ — antes no se exigía en ninguna parte,
            // pese a lo que afirma el comentario de UserRequest ("Presence on create is enforced in
            // UserServiceImpl").
            //
            // Lo que pasaba sin esto: `encode(null)` NO lanza —en Spring Security 7,
            // AbstractValidatingPasswordEncoder.encode devuelve null tal cual— así que la contraseña
            // quedaba nula y lo único que avisaba era el NOT NULL de la columna, con un 500 de violación de
            // restricción. Y desde que V22 relaja ese NOT NULL para poder invitar, ese 500 desaparece: sin
            // esta comprobación se crearía en silencio, con un 200, un administrador sin contraseña y sin
            // token de invitación — una cuenta en la que nadie puede entrar nunca y a la que nadie fue
            // invitado. Así que la validación no es cosmética: la hace necesaria la migración.
            if (command.password() == null || command.password().isBlank()) {
                throw new BusinessException("Indica una contraseña o activa el envío de invitación");
            }
            user.setPassword(passwordEncoder.encode(command.password()));
        }

        if (command.avatar() != null && !command.avatar().isEmpty()) {
            user.setAvatar(fileStorageService.store(command.avatar(), "avatars"));
        }
        User saved = userRepository.save(user);
        if (command.roleIds() != null) {
            saved.setRoles(resolveRoles(command.roleIds()));
        }
        saved = userRepository.save(saved);

        if (command.sendInvitation()) {
            invitationMailer.send(saved, store);
        }
        return saved;
    }

    /**
     * PROD-06. Vuelve a enviar la invitación, con un token nuevo.
     *
     * <p>Hasta ahora, si los 7 días se agotaban, la única salida era <b>borrar al usuario y crearlo otra
     * vez</b> — lo que le cambia el id, pierde sus notas y deja huérfano cualquier rastro que lo
     * referenciara. Reenviar una invitación caducada es justamente el caso de uso principal, no una
     * excepción.
     *
     * <p>El token anterior <b>deja de valer</b>: se genera uno nuevo, y como la búsqueda es por
     * {@code invitationToken}, el enlace viejo ya no encuentra nada. Es lo que pide el criterio
     * («invalidación del anterior») y también lo correcto: si alguien reenvía porque sospecha que el
     * primer correo acabó donde no debía, dejar los dos vivos no arreglaría nada.
     *
     * <p>Tres cosas lo rechazan: que la cuenta ya tenga contraseña (no hay nada que invitar; quien la
     * perdió usa «olvidé mi contraseña»), que esté desactivada (mandar un enlace a una cuenta en la que
     * {@code adminAcceptInvitation} no deja entrar sería mentirle al invitado), y que no haya pasado el
     * intervalo mínimo.
     */
    @Override
    @Transactional
    public User resendInvitation(Long id) {
        Store store = TenantContext.requireCurrent();
        User user = userRepository.findById(id)
                .filter(u -> u.getStore().getId().equals(store.getId()))
                .orElseThrow(() -> new NoSuchElementException("User " + id + " not found"));

        if (user.getPassword() != null || user.getInvitationAcceptedAt() != null) {
            throw new BusinessException("Esta cuenta ya tiene contraseña: usa la recuperación de contraseña");
        }
        if (!user.isActive()) {
            throw new BusinessException("La cuenta está desactivada: actívala antes de reenviar la invitación");
        }
        Duration remaining = invitationPolicy.resendCooldownRemaining(user);
        if (remaining != null) {
            long minutesLeft = Math.max(1, remaining.toMinutes() + 1);
            throw new BusinessException("La invitación se envió hace poco: espera "
                    + minutesLeft + (minutesLeft == 1 ? " minuto" : " minutos") + " antes de reenviarla");
        }

        invitationMailer.issue(user);
        User saved = userRepository.save(user);
        invitationMailer.send(saved, store);
        return saved;
    }

    @Override
    @Transactional
    public User updateUser(Long id, UserCreateCommand command) {
        User user = userRepository.findById(id)
                .filter(u -> u.getStore().getId().equals(TenantContext.requireStoreId()))
                .orElseThrow(() -> new NoSuchElementException("User " + id + " not found"));
        applyCommonFields(user, command);
        if (command.password() != null && !command.password().isBlank()) {
            user.setPassword(passwordEncoder.encode(command.password()));
        }
        if (command.avatar() != null && !command.avatar().isEmpty()) {
            if (user.getAvatar() != null) {
                fileStorageService.delete(user.getAvatar());
            }
            user.setAvatar(fileStorageService.store(command.avatar(), "avatars"));
        }
        // A5: a changed password or a changed role set has to invalidate sessions already open —
        // permissions are frozen into the JWT at login, so without this a demoted admin keeps the
        // old ones until the token expires. A plain profile edit deliberately doesn't revoke:
        // logging someone out for renaming themselves would be gratuitous.
        boolean credentialsOrRolesChanged = command.password() != null && !command.password().isBlank();
        if (command.roleIds() != null) {
            user.setRoles(resolveRoles(command.roleIds()));
            credentialsOrRolesChanged = true;
        }
        if (credentialsOrRolesChanged) {
            revokeTokens(user);
        }
        return userRepository.save(user);
    }

    @Override
    @Transactional
    public void deactivateUser(Long id) {
        User user = userRepository.findById(id)
                .filter(u -> u.getStore().getId().equals(TenantContext.requireStoreId()))
                .orElseThrow(() -> new NoSuchElementException("User " + id + " not found"));
        user.setActive(false);
        revokeTokens(user);
        userRepository.save(user);
    }

    @Override
    @Transactional
    public void assignRoles(Long userId, Set<Long> roleIds) {
        User user = userRepository.findById(userId)
                .filter(u -> u.getStore().getId().equals(TenantContext.requireStoreId()))
                .orElseThrow(() -> new NoSuchElementException("User " + userId + " not found"));
        user.setRoles(resolveRoles(roleIds));
        revokeTokens(user);
        userRepository.save(user);
    }

    @Override
    @Transactional
    public User toggleActive(Long id) {
        User user = userRepository.findById(id)
                .filter(u -> u.getStore().getId().equals(TenantContext.requireStoreId()))
                .orElseThrow(() -> new NoSuchElementException("User " + id + " not found"));
        user.setActive(!user.isActive());
        revokeTokens(user);
        return userRepository.save(user);
    }

    @Override
    @Transactional
    public void deleteUser(Long id) {
        User user = userRepository.findById(id)
                .filter(u -> u.getStore().getId().equals(TenantContext.requireStoreId()))
                .orElseThrow(() -> new NoSuchElementException("User " + id + " not found"));
        userRepository.delete(user);
        // No version to bump — the row is gone, so currentVersion() answers -1 and nothing matches.
        // The eviction is what matters: a cached version would otherwise keep a deleted admin's
        // token working until the entry expired.
        tokenVersionService.evict(TokenVersionService.ADMIN, id);
    }

    // Bumps in memory so the caller's own save() persists it in the same transaction, and drops the
    // cached value so revocation takes effect on the very next request.
    private void revokeTokens(User user) {
        user.setTokenVersion(user.getTokenVersion() + 1);
        tokenVersionService.evict(TokenVersionService.ADMIN, user.getId());
    }

    private void applyCommonFields(User user, UserCreateCommand command) {
        user.setName(command.name());
        user.setEmail(command.email());
        user.setPhone(command.phone());
        user.setAdmin(command.isAdmin());
        user.setActive(command.active());
        user.setNotes(command.notes());
    }

    private Set<Role> resolveRoles(Set<Long> roleIds) {
        Long storeId = TenantContext.requireStoreId();
        Set<Role> roles = new HashSet<>();
        for (Long roleId : roleIds) {
            roles.add(roleRepository.findById(roleId)
                    .filter(r -> r.getStore().getId().equals(storeId))
                    .orElseThrow(() -> new NoSuchElementException("Role " + roleId + " not found")));
        }
        return roles;
    }
}
