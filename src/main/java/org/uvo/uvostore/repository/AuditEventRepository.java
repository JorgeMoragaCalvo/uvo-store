package org.uvo.uvostore.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.uvo.uvostore.entity.audit.AuditEvent;

/**
 * PROD-18. Escribe solo {@code AuditTrail}. PROD-23: la lee la API de plataforma
 * ({@code PlatformStoreQueryService}, siempre filtrando por tienda); el panel de la tienda no la lee.
 */
public interface AuditEventRepository extends JpaRepository<AuditEvent, Long>, JpaSpecificationExecutor<AuditEvent> {
}
