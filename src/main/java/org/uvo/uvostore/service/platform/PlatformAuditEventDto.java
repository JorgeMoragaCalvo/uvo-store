package org.uvo.uvostore.service.platform;

import org.uvo.uvostore.entity.audit.AuditEvent;
import org.uvo.uvostore.entity.audit.enums.AuditAction;
import org.uvo.uvostore.entity.audit.enums.AuditActorType;
import org.uvo.uvostore.entity.audit.enums.AuditResult;
import org.uvo.uvostore.entity.audit.enums.AuditTargetType;

import java.time.Instant;
import java.util.Map;

/**
 * PROD-23. Una fila de {@code audit_events} para el backoffice del POS. Todas las columnas: {@code details} ya nace
 * sin secretos ni cuerpos completos (PROD-18, {@code AuditDiff}), así que no hay nada que filtrar al leerla.
 */
public record PlatformAuditEventDto(
        Long id,
        Instant occurredAt,
        Long storeId,
        AuditActorType actorType,
        Long actorId,
        String actorLabel,
        AuditAction action,
        AuditTargetType targetType,
        Long targetId,
        AuditResult result,
        String requestId,
        Map<String, Object> details
) {

    static PlatformAuditEventDto of(AuditEvent event) {
        return new PlatformAuditEventDto(event.getId(), event.getOccurredAt(), event.getStoreId(), event.getActorType(),
                event.getActorId(), event.getActorLabel(), event.getAction(), event.getTargetType(), event.getTargetId(),
                event.getResult(), event.getRequestId(), event.getDetails());
    }
}
