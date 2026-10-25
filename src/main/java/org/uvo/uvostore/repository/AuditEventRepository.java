package org.uvo.uvostore.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.uvo.uvostore.entity.audit.AuditEvent;

/**
 * PROD-18. Solo escritura: la aplicación no lee la auditoría todavía (las consultas están en V28). Lo usa
 * {@code AuditTrail}, y nadie más.
 */
public interface AuditEventRepository extends JpaRepository<AuditEvent, Long> {
}
