package org.uvo.uvostore.repository;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.uvo.uvostore.entity.pos.ProductSyncMapping;
import org.uvo.uvostore.entity.pos.enums.SyncStatus;

import java.time.Instant;
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

    /**
     * POS-3. Marca el mapeo como sincronizado sin tocar nada más.
     *
     * <p>El webhook de stock la usa en vez de {@code save}. Su {@code setStockIfUnchanged} limpia el contexto,
     * así que su mapeo queda desconectado, y el merge de un {@code save} copiaría <b>todas</b> sus columnas:
     * también una {@code catalog_version} leída antes de que otra entrada confirmara una más nueva. Tampoco fija
     * {@code ACTIVE}: un mapeo pausado entretanto se quedaría reactivado.
     */
    @Modifying(flushAutomatically = true)
    @Query("update ProductSyncMapping m set m.lastSyncedAt = :now, m.syncError = null, m.updatedAt = :now where m.id = :id")
    int touchSynced(@Param("id") Long id, @Param("now") Instant now);

    /**
     * POS-4. Un lote de mapeos activos de una empresa para la conciliación, con su producto. Keyset por id y no
     * offset: entre un lote y el siguiente pueden nacer o pausarse mapeos, y con offset alguno se saltaría.
     */
    @Query("""
            select m from ProductSyncMapping m
            join fetch m.product
            where m.companyId = :companyId
              and m.syncStatus = org.uvo.uvostore.entity.pos.enums.SyncStatus.ACTIVE
              and m.id > :afterId
            order by m.id asc
            """)
    List<ProductSyncMapping> findActiveBatch(@Param("companyId") Long companyId, @Param("afterId") Long afterId,
                                             Pageable pageable);
}
