package org.uvo.uvostore.service.platform;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.uvo.uvostore.entity.audit.enums.AuditAction;
import org.uvo.uvostore.entity.audit.enums.AuditTargetType;
import org.uvo.uvostore.entity.security.User;
import org.uvo.uvostore.entity.tenant.Store;
import org.uvo.uvostore.entity.tenant.enums.StoreStatus;
import org.uvo.uvostore.repository.StoreRepository;
import org.uvo.uvostore.repository.UserRepository;
import org.uvo.uvostore.security.TokenVersionService;
import org.uvo.uvostore.service.BusinessException;
import org.uvo.uvostore.service.audit.AuditDiff;
import org.uvo.uvostore.service.audit.AuditEntry;
import org.uvo.uvostore.service.audit.AuditTrail;
import org.uvo.uvostore.service.security.AdminInvitationMailer;
import org.uvo.uvostore.service.security.AdminInvitationPolicy;

import java.time.Duration;
import java.time.Instant;
import java.util.NoSuchElementException;

/**
 * PROD-23. Las operaciones de plataforma sobre una tienda que no la cierran. Cada una lee la tienda con su fila
 * bloqueada ({@link StoreRepository#findLockedById}): dos llamadas simultáneas del POS se ordenan en vez de pisarse.
 *
 * <p>Toda fila de auditoría nombra su tienda ({@code forStore}): en {@code /api/platform/**} el tenant del contexto es
 * el del Host, que no tiene por qué ser la tienda de la que se habla.
 */
@Service
public class StoreLifecycleServiceImpl implements StoreLifecycleService {

    private final StoreRepository storeRepository;
    private final UserRepository userRepository;
    private final StoreOwnerRole storeOwnerRole;
    private final AdminInvitationMailer invitationMailer;
    private final AdminInvitationPolicy invitationPolicy;
    private final TokenVersionService tokenVersionService;
    private final ApplicationEventPublisher events;
    private final AuditTrail auditTrail;
    private final PlatformStoreMapper mapper;

    public StoreLifecycleServiceImpl(StoreRepository storeRepository, UserRepository userRepository,
                                     StoreOwnerRole storeOwnerRole, AdminInvitationMailer invitationMailer,
                                     AdminInvitationPolicy invitationPolicy, TokenVersionService tokenVersionService,
                                     ApplicationEventPublisher events, AuditTrail auditTrail,
                                     PlatformStoreMapper mapper) {
        this.storeRepository = storeRepository;
        this.userRepository = userRepository;
        this.storeOwnerRole = storeOwnerRole;
        this.invitationMailer = invitationMailer;
        this.invitationPolicy = invitationPolicy;
        this.tokenVersionService = tokenVersionService;
        this.events = events;
        this.auditTrail = auditTrail;
        this.mapper = mapper;
    }

    /**
     * La tienda deja de atender por su Host: storefront, panel y API pública responden 403
     * ({@code TenantResolutionFilter}). Lo que ya estaba en vuelo termina: pasan los retornos y avisos de pago, y los
     * jobs de pagos, stock y outbox siguen. La conciliación con el POS se pausa, y sus entradas siguen llegando.
     *
     * <p><b>No cierra sesiones</b>: al reactivar, los tokens vigentes vuelven a servir. Mientras tanto no sirven de nada,
     * porque el bloqueo es por Host y no por token.
     */
    @Override
    @Transactional
    public PlatformStoreDto suspend(Long storeId, StoreStatusReason reason) {
        Store store = lockedStore(storeId);
        if (store.isClosed()) {
            throw new BusinessException("La tienda está cerrada: no se puede suspender");
        }
        if (!store.isSuspended()) {
            changeStatus(store, StoreStatus.SUSPENDED, reason);
        }
        return mapper.toDto(store);
    }

    @Override
    @Transactional
    public PlatformStoreDto reactivate(Long storeId) {
        Store store = lockedStore(storeId);
        if (store.isClosed()) {
            throw new BusinessException("Una tienda cerrada no se puede reactivar");
        }
        if (store.isSuspended()) {
            changeStatus(store, StoreStatus.ACTIVE, null);
        }
        return mapper.toDto(store);
    }

    private void changeStatus(Store store, StoreStatus to, StoreStatusReason reason) {
        StoreStatus from = store.getStatus();
        store.setStatus(to);
        store.setStatusChangedAt(Instant.now());
        storeRepository.save(store);
        auditTrail.record(AuditEntry.of(AuditAction.STORE_STATUS_UPDATE, AuditTargetType.STORE, store.getId())
                .forStore(store.getId())
                .details(AuditDiff.create().value("status", from, to).with("reason", reason)));
    }

    /**
     * Para cuando el dueño no puede entrar: perdió la contraseña, nunca le llegó la invitación, otro administrador lo
     * desactivó o le quitó el rol, o su correo ya no sirve. Deja la cuenta como recién invitada:
     *
     * <ul>
     *   <li>activa y administradora, con el rol de dueño ({@link StoreOwnerRole#ensureAssigned});</li>
     *   <li>sin contraseña ni enlace de recuperación vivos: hasta que acepte, nadie entra con la cuenta, tampoco quien
     *       tuviera la contraseña vieja;</li>
     *   <li>sin sesiones: sube {@code tokenVersion};</li>
     *   <li>con una invitación nueva, que invalida la anterior. {@code invitationAcceptedAt} vuelve a nulo para que se
     *       lea como pendiente, también en el panel, cuyo reenvío entonces funciona.</li>
     * </ul>
     *
     * <p>Solo con la tienda activa: con una suspendida el enlace llevaría a un 403. La espera entre reenvíos
     * ({@link AdminInvitationPolicy#resendCooldownRemaining}) aplica si el correo no cambia: dos clics seguidos en el
     * backoffice no mandan dos correos, el segundo invalidando el primero. Cambiar el correo no espera: es justo lo
     * que se hace cuando el anterior estaba mal.
     *
     * <p>El correo sale <b>después del commit</b>, con el mismo evento y oyente que el alta
     * ({@link StoreOwnerInvitedEvent}): si esto se deshiciera, el enlace llevaría un token que no existe.
     */
    @Override
    @Transactional
    public PlatformStoreDto recoverOwnerAccess(Long storeId, String newEmail) {
        Store store = lockedStore(storeId);
        if (store.isClosed()) {
            throw new BusinessException("La tienda está cerrada");
        }
        if (store.isSuspended()) {
            throw new BusinessException("La tienda está suspendida: reactívala antes de recuperar el acceso del propietario");
        }
        User owner = store.getOwnerUserId() == null ? null : userRepository.findById(store.getOwnerUserId())
                .filter(user -> user.getStore().getId().equals(store.getId()))
                .orElse(null);
        if (owner == null) {
            throw new BusinessException("La tienda no tiene un propietario registrado");
        }

        String previousEmail = owner.getEmail();
        String email = newEmail == null || newEmail.isBlank() ? previousEmail : newEmail.trim();
        boolean emailChanged = !email.equalsIgnoreCase(previousEmail);
        if (emailChanged) {
            userRepository.findByStoreIdAndEmail(store.getId(), email)
                    .filter(other -> !other.getId().equals(owner.getId()))
                    .ifPresent(other -> {
                        throw new BusinessException("Ese correo ya lo usa otro administrador de la tienda");
                    });
        } else {
            Duration remaining = invitationPolicy.resendCooldownRemaining(owner);
            if (remaining != null) {
                long minutesLeft = Math.max(1, remaining.toMinutes() + 1);
                throw new BusinessException("La invitación se envió hace poco: espera "
                        + minutesLeft + (minutesLeft == 1 ? " minuto" : " minutos") + " antes de reenviarla");
            }
        }

        boolean reactivated = !owner.isActive();
        boolean passwordCleared = owner.getPassword() != null;
        owner.setEmail(email);
        owner.setActive(true);
        owner.setAdmin(true);
        owner.setPassword(null);
        owner.setPasswordResetToken(null);
        owner.setPasswordResetExpiresAt(null);
        owner.setInvitationAcceptedAt(null);
        boolean roleRestored = storeOwnerRole.ensureAssigned(owner, store);
        invitationMailer.issue(owner);
        owner.setTokenVersion(owner.getTokenVersion() + 1);
        User saved = userRepository.save(owner);
        tokenVersionService.evict(TokenVersionService.ADMIN, saved.getId());

        // Del token, nada: es lo único que separa a un desconocido del panel.
        auditTrail.record(AuditEntry.of(AuditAction.STORE_OWNER_ACCESS_RECOVERY, AuditTargetType.USER, saved.getId())
                .forStore(store.getId())
                .details(AuditDiff.create()
                        .value("email", previousEmail, saved.getEmail())
                        .with("reactivated", reactivated)
                        .with("passwordCleared", passwordCleared)
                        .with("roleRestored", roleRestored)
                        .with("sessionsRevoked", true)));

        events.publishEvent(new StoreOwnerInvitedEvent(saved.getId()));
        return mapper.toDto(store);
    }

    private Store lockedStore(Long storeId) {
        return storeRepository.findLockedById(storeId)
                .orElseThrow(() -> new NoSuchElementException("Store " + storeId + " not found"));
    }
}
