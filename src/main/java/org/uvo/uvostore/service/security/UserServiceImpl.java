package org.uvo.uvostore.service.security;

import io.sentry.Sentry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import org.uvo.uvostore.service.notification.EmailService;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;

@Service
public class UserServiceImpl implements UserService {

    private static final Logger log = LoggerFactory.getLogger(UserServiceImpl.class);

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;
    private final FileStorageService fileStorageService;
    private final TokenVersionService tokenVersionService;
    private final EmailService emailService;
    private final String frontendUrl;
    // Más corta que los 30 días de la invitación de cliente (F24): esto entrega acceso al panel, donde se
    // emiten reembolsos y se configuran credenciales de pasarela.
    private final Duration invitationTtl;

    public UserServiceImpl(UserRepository userRepository, RoleRepository roleRepository,
                            PasswordEncoder passwordEncoder, FileStorageService fileStorageService,
                            TokenVersionService tokenVersionService, EmailService emailService,
                            @Value("${app.frontend-url}") String frontendUrl,
                            @Value("${app.admin-invitation.ttl-days:7}") int invitationTtlDays) {
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.passwordEncoder = passwordEncoder;
        this.fileStorageService = fileStorageService;
        this.tokenVersionService = tokenVersionService;
        this.emailService = emailService;
        this.frontendUrl = frontendUrl;
        this.invitationTtl = Duration.ofDays(invitationTtlDays);
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
            user.setInvitationToken(UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", ""));
            user.setInvitationSentAt(Instant.now());
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
            sendInvitationEmail(saved, store);
        }
        return saved;
    }

    /**
     * El correo con el enlace para elegir contraseña.
     *
     * <p>Los fallos se registran y no se propagan <b>a propósito</b>: el administrador ya está creado y su
     * token sigue siendo válido, así que tumbar el alta por un SMTP caído sería perder trabajo bueno. Si el
     * correo no sale queda en el log y en Sentry, y el token se puede entregar a mano mientras no exista
     * una pantalla de reenvío — que hoy no existe, y está anotado como pendiente.
     */
    private void sendInvitationEmail(User user, Store store) {
        try {
            emailService.send(user.getEmail(), "Te invitaron a administrar " + store.getName(),
                    invitationBody(user, store));
        } catch (Exception e) {
            log.error("Error enviando la invitación de administrador user_id={} error={}", user.getId(), e.getMessage());
            Sentry.captureException(e);
        }
    }

    /**
     * Visible en el paquete para poder afirmar sobre el cuerpo sin enviar nada: en los tests no hay SMTP y
     * {@code EmailServiceImpl} registra y omite el envío. Mismo recurso que
     * {@code CustomerInvitationEmailListener.body} y {@code MercadoPagoServiceImpl.buildPreferenceRequest}.
     */
    String invitationBody(User user, Store store) {
        return "Hola " + user.getName() + ",\n\n"
                + "Te dieron acceso al panel de administración de " + store.getName() + ". "
                + "Elige tu contraseña aquí para entrar:\n\n"
                + frontendUrl + "/admin/aceptar-invitacion?token=" + user.getInvitationToken() + "\n\n"
                + "El enlace es de un solo uso y caduca en " + invitationTtl.toDays() + " días. "
                + "Nadie más conoce tu contraseña: la eliges tú.";
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
