package org.uvo.uvostore.service.platform;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.uvo.uvostore.entity.audit.enums.AuditAction;
import org.uvo.uvostore.entity.tenant.enums.StoreStatus;

import java.time.Instant;

/** PROD-23. Lo que la plataforma lee de las tiendas: la lista, el detalle y la auditoría de cada una. */
public interface PlatformStoreQueryService {

    /**
     * @param search nick, nombre, dominio o correo del dueño, sin distinguir mayúsculas; nulo o vacío = todas
     * @param status nulo = cualquiera
     * @param companyId la empresa de UvoPOS conectada; nulo = cualquiera
     */
    Page<PlatformStoreDto> list(String search, StoreStatus status, Long companyId, Pageable pageable);

    PlatformStoreDto get(Long storeId);

    /**
     * La auditoría de una tienda, de lo más nuevo a lo más viejo.
     *
     * @param from desde, inclusive; nulo = sin límite
     * @param to hasta, exclusive; nulo = sin límite
     */
    Page<PlatformAuditEventDto> auditEvents(Long storeId, AuditAction action, Instant from, Instant to, Pageable pageable);
}
