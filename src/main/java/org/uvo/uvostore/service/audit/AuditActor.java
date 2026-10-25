package org.uvo.uvostore.service.audit;

import org.uvo.uvostore.entity.audit.enums.AuditActorType;
import org.uvo.uvostore.entity.security.User;

/**
 * PROD-18. Quién hizo el cambio. Casi siempre lo deduce {@link AuditTrail} del contexto de seguridad; se pasa
 * explícito solo donde ese contexto no dice la verdad (ver {@link AuditEntry#by}).
 *
 * @param label el correo del admin tal como era en ese momento, o el operador de plataforma
 */
public record AuditActor(AuditActorType type, Long id, String label) {

    public static final AuditActor SYSTEM = new AuditActor(AuditActorType.SYSTEM, null, null);

    public static AuditActor admin(User user) {
        return new AuditActor(AuditActorType.ADMIN, user.getId(), user.getEmail());
    }
}
