package org.uvo.uvostore.service.platform;

import jakarta.persistence.criteria.Subquery;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.uvo.uvostore.entity.audit.AuditEvent;
import org.uvo.uvostore.entity.audit.enums.AuditAction;
import org.uvo.uvostore.entity.pos.PosConnection;
import org.uvo.uvostore.entity.security.User;
import org.uvo.uvostore.entity.tenant.Store;
import org.uvo.uvostore.entity.tenant.enums.StoreStatus;
import org.uvo.uvostore.repository.AuditEventRepository;
import org.uvo.uvostore.repository.StoreRepository;
import org.uvo.uvostore.service.BusinessException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * PROD-23. Las lecturas de la plataforma. Fuera de toda tienda: la ruta de plataforma no tiene tenant propio (el que
 * deja {@code TenantResolutionFilter} es el del Host, que puede ser cualquiera), así que nada aquí lee
 * {@code TenantContext}; la tienda llega siempre como parámetro.
 */
@Service
public class PlatformStoreQueryServiceImpl implements PlatformStoreQueryService {

    private final StoreRepository storeRepository;
    private final AuditEventRepository auditEventRepository;
    private final PlatformStoreMapper mapper;

    public PlatformStoreQueryServiceImpl(StoreRepository storeRepository, AuditEventRepository auditEventRepository,
                                         PlatformStoreMapper mapper) {
        this.storeRepository = storeRepository;
        this.auditEventRepository = auditEventRepository;
        this.mapper = mapper;
    }

    @Override
    @Transactional(readOnly = true)
    public Page<PlatformStoreDto> list(String search, StoreStatus status, Long companyId, Pageable pageable) {
        List<Specification<Store>> specs = new ArrayList<>();
        if (search != null && !search.isBlank()) {
            String term = "%" + search.trim().toLowerCase() + "%";
            specs.add((root, query, cb) -> {
                // El correo del dueño por subconsulta: owner_user_id es un número en la tienda, no una relación.
                Subquery<Long> owner = query.subquery(Long.class);
                var user = owner.from(User.class);
                owner.select(user.get("id")).where(
                        cb.equal(user.get("id"), root.get("ownerUserId")),
                        cb.like(cb.lower(user.get("email")), term));
                return cb.or(
                        cb.like(cb.lower(root.get("slug")), term),
                        cb.like(cb.lower(root.get("name")), term),
                        cb.like(cb.lower(root.get("domain")), term),
                        cb.exists(owner));
            });
        }
        if (status != null) {
            specs.add((root, query, cb) -> cb.equal(root.get("status"), status));
        }
        if (companyId != null) {
            specs.add((root, query, cb) -> {
                Subquery<Long> connection = query.subquery(Long.class);
                var pos = connection.from(PosConnection.class);
                connection.select(pos.get("id")).where(
                        cb.equal(pos.get("store"), root),
                        cb.equal(pos.get("companyId"), companyId));
                return cb.exists(connection);
            });
        }

        Specification<Store> spec = specs.stream().reduce(Specification::and).orElse(null);
        Page<Store> page = storeRepository.findAll(spec, pageable);
        return new PageImpl<>(mapper.toDtos(page.getContent()), page.getPageable(), page.getTotalElements());
    }

    @Override
    @Transactional(readOnly = true)
    public PlatformStoreDto get(Long storeId) {
        return mapper.toDto(storeRepository.findById(storeId)
                .orElseThrow(() -> new NoSuchElementException("Store " + storeId + " not found")));
    }

    @Override
    @Transactional(readOnly = true)
    public Page<PlatformAuditEventDto> auditEvents(Long storeId, AuditAction action, Instant from, Instant to,
                                                   Pageable pageable) {
        if (!storeRepository.existsById(storeId)) {
            throw new NoSuchElementException("Store " + storeId + " not found");
        }
        if (from != null && to != null && from.isAfter(to)) {
            throw new BusinessException("La fecha desde no puede ser posterior a la fecha hasta");
        }
        // La tienda siempre: es lo que impide leer la auditoría de otra.
        List<Specification<AuditEvent>> specs = new ArrayList<>();
        specs.add((root, query, cb) -> cb.equal(root.get("storeId"), storeId));
        if (action != null) {
            specs.add((root, query, cb) -> cb.equal(root.get("action"), action));
        }
        if (from != null) {
            specs.add((root, query, cb) -> cb.greaterThanOrEqualTo(root.get("occurredAt"), from));
        }
        if (to != null) {
            specs.add((root, query, cb) -> cb.lessThan(root.get("occurredAt"), to));
        }
        Specification<AuditEvent> spec = specs.stream().reduce(Specification::and).orElseThrow();
        return auditEventRepository.findAll(spec, pageable).map(PlatformAuditEventDto::of);
    }
}
