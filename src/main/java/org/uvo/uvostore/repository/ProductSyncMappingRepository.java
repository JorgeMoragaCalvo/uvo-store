package org.uvo.uvostore.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.uvo.uvostore.entity.pos.ProductSyncMapping;
import org.uvo.uvostore.entity.pos.enums.SyncStatus;

import java.util.List;
import java.util.Optional;

public interface ProductSyncMappingRepository extends JpaRepository<ProductSyncMapping, Long> {

    Optional<ProductSyncMapping> findByProductId(Long productId);
    Optional<ProductSyncMapping> findByExternalIdAndCompanyId(Long externalId, Long companyId);
    Optional<ProductSyncMapping> findByProductIdAndCompanyId(Long productId, Long companyId);
    List<ProductSyncMapping> findByCompanyIdAndSyncStatus(Long companyId, SyncStatus status);
}
