package org.uvo.uvostore.service.audit;

import java.util.Map;
import org.uvo.uvostore.entity.audit.enums.AuditAction;
import org.uvo.uvostore.entity.audit.enums.AuditResult;
import org.uvo.uvostore.entity.audit.enums.AuditTargetType;

/**
 * PROD-18. Lo que se va a registrar, armado por quien hace el cambio y entregado a {@link AuditTrail#record}.
 *
 * <p>{@code storeId} y {@code actor} nulos significan "los del contexto": la tienda de {@code TenantContext} y el
 * principal de Spring Security. {@code details} solo se arma con {@link AuditDiff}, que es lo que garantiza que no
 * lleve secretos ni cuerpos completos.
 */
public record AuditEntry(AuditAction action, AuditResult result, AuditTargetType targetType, Long targetId,
                         Long storeId, AuditActor actor, Map<String, Object> details) {

    public static AuditEntry of(AuditAction action, AuditTargetType targetType, Long targetId) {
        return new AuditEntry(action, AuditResult.SUCCESS, targetType, targetId, null, null, null);
    }

    public AuditEntry failed() {
        return new AuditEntry(action, AuditResult.FAILURE, targetType, targetId, storeId, actor, details);
    }

    public AuditEntry details(AuditDiff diff) {
        return new AuditEntry(action, result, targetType, targetId, storeId, actor, diff.toMap());
    }

    /**
     * La tienda sobre la que recae el cambio, cuando no es la del Host: las llamadas de plataforma (el Host puede ser
     * el de otra tienda, o ninguno) y el restablecimiento de contraseña (busca el token sin mirar el Host).
     */
    public AuditEntry forStore(Long storeId) {
        return new AuditEntry(action, result, targetType, targetId, storeId, actor, details);
    }

    /**
     * El actor, cuando el contexto de seguridad no es quien actúa: en el login, la invitación y el restablecimiento
     * el panel manda igual el Bearer que tenga guardado, y ese token puede ser de otro admin.
     */
    public AuditEntry by(AuditActor actor) {
        return new AuditEntry(action, result, targetType, targetId, storeId, actor, details);
    }
}
