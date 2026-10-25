package org.uvo.uvostore.service.audit;

import org.slf4j.MDC;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.uvo.uvostore.config.RequestIdFilter;
import org.uvo.uvostore.entity.audit.AuditEvent;
import org.uvo.uvostore.entity.audit.enums.AuditActorType;
import org.uvo.uvostore.repository.AuditEventRepository;
import org.uvo.uvostore.security.AuthPrincipal;
import org.uvo.uvostore.security.PlatformApiKeyAuthFilter;
import org.uvo.uvostore.security.TenantContext;
import org.uvo.uvostore.security.TokenVersionService;

/**
 * PROD-18. Escribe el registro de auditoría. Es lo único que escribe en {@code audit_events}.
 *
 * <p><b>Misma transacción que el cambio</b>, y por eso {@link Propagation#MANDATORY}: llamarlo fuera de una
 * transacción es un error, no una fila suelta. Si el cambio se deshace, la fila también, y si la fila no se puede
 * escribir, el cambio tampoco se hace. Las dos filas que sobreviven a un fallo lo hacen porque su transacción
 * confirma: el login fallido ({@code noRollbackFor} en {@code AuthController.adminLogin}) y el reembolso rechazado
 * (dentro de {@code RefundIntentStore.fail}, que ya corría en una transacción propia). No hay variante
 * {@code REQUIRES_NEW}: tomaría una segunda conexión por cada login fallido, y diez a la vez agotan el pool.
 *
 * <p><b>Quién:</b> el actor explícito de la entrada, si lo trae; si no, el principal de Spring Security: un admin
 * del panel (id y correo) o la plataforma (con su operador, {@code PlatformApiKeyAuthFilter}). Sin autenticación,
 * el sistema. Un comprador no hace cambios auditados, y si llegara a hacerlo es un error.
 *
 * <p><b>Dónde:</b> la tienda explícita de la entrada, o la de {@code TenantContext}. La plataforma tiene que decirla
 * siempre: {@code TenantResolutionFilter} resuelve el Host también en {@code /api/platform/**}, y la tienda del Host
 * no es la que se está creando o cambiando.
 */
@Service
public class AuditTrail {

    static final int MAX_LABEL = 255;

    private final AuditEventRepository repository;

    public AuditTrail(AuditEventRepository repository) {
        this.repository = repository;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void record(AuditEntry entry) {
        AuditActor actor = entry.actor() != null ? entry.actor() : currentActor();
        repository.save(AuditEvent.builder()
                .storeId(storeOf(entry, actor))
                .actorType(actor.type())
                .actorId(actor.id())
                .actorLabel(truncate(actor.label()))
                .action(entry.action())
                .targetType(entry.targetType())
                .targetId(entry.targetId())
                .result(entry.result())
                .requestId(MDC.get(RequestIdFilter.MDC_KEY))
                .details(entry.details())
                .build());
    }

    private static AuditActor currentActor() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof AuthPrincipal principal)) {
            return AuditActor.SYSTEM;
        }
        return switch (principal.type()) {
            case TokenVersionService.ADMIN -> new AuditActor(AuditActorType.ADMIN, principal.id(), principal.subject());
            case PlatformApiKeyAuthFilter.PRINCIPAL_TYPE -> new AuditActor(AuditActorType.PLATFORM, null, principal.subject());
            default -> throw new IllegalStateException(
                    "Un principal " + principal.type() + " no hace cambios auditados (PROD-18)");
        };
    }

    private static Long storeOf(AuditEntry entry, AuditActor actor) {
        if (entry.storeId() != null) {
            return entry.storeId();
        }
        if (actor.type() == AuditActorType.PLATFORM) {
            throw new IllegalStateException(
                    "Una acción de plataforma tiene que nombrar su tienda (AuditEntry.forStore): " + entry.action());
        }
        Long storeId = TenantContext.currentStoreId();
        if (storeId == null && actor.type() == AuditActorType.ADMIN) {
            throw new IllegalStateException("Una acción de un admin sin tienda: " + entry.action());
        }
        return storeId;
    }

    private static String truncate(String label) {
        return label == null || label.length() <= MAX_LABEL ? label : label.substring(0, MAX_LABEL);
    }
}
