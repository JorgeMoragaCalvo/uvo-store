package org.uvo.uvostore.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.uvo.uvostore.entity.pos.ProductSyncMapping;
import org.uvo.uvostore.entity.pos.enums.SyncStatus;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ProductSyncMappingRepository extends JpaRepository<ProductSyncMapping, Long> {

    // PROD-13b: sustituye a findByProductId, que devolvía un Optional sobre una columna que NO es única —un
    // producto puede estar mapeado a dos empresas (la restricción es (product_id, company_id))— y lanzaba
    // IncorrectResultSizeDataAccessException en ese caso. Ahora se consulta dentro de la transacción del pago
    // (OrderStatusServiceImpl.markPaid), donde esa excepción desharía la confirmación entera: una lista no
    // puede fallar por duplicados.
    List<ProductSyncMapping> findByProductIdIn(Collection<Long> productIds);
    Optional<ProductSyncMapping> findByExternalIdAndCompanyId(Long externalId, Long companyId);
    Optional<ProductSyncMapping> findByProductIdAndCompanyId(Long productId, Long companyId);
    List<ProductSyncMapping> findByCompanyIdAndSyncStatus(Long companyId, SyncStatus status);
}
