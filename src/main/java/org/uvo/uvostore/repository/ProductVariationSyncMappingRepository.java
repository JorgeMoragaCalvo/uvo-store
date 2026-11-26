package org.uvo.uvostore.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.uvo.uvostore.entity.pos.ProductVariationSyncMapping;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * POS-5. Los mapeos de variante. IMP-08: cada uno es un Producto del POS ({@code external_variant_id}), único por
 * empresa.
 */
public interface ProductVariationSyncMappingRepository extends JpaRepository<ProductVariationSyncMapping, Long> {

    /** Las variantes de un producto del POS, con su variación: lo que lee el escritor de catálogo. */
    @Query("""
            select vm from ProductVariationSyncMapping vm
            join fetch vm.variation
            where vm.mapping.id = :mappingId
            order by vm.id
            """)
    List<ProductVariationSyncMapping> findByMappingId(@Param("mappingId") Long mappingId);

    @Query("""
            select vm from ProductVariationSyncMapping vm
            join fetch vm.variation
            where vm.mapping.id = :mappingId and vm.externalVariantId = :externalVariantId
            """)
    Optional<ProductVariationSyncMapping> findByMappingIdAndExternalVariantId(
            @Param("mappingId") Long mappingId, @Param("externalVariantId") Long externalVariantId);

    @Query("""
            select vm from ProductVariationSyncMapping vm
            where vm.mapping.id = :mappingId and vm.variation.id = :variationId
            """)
    Optional<ProductVariationSyncMapping> findByMappingIdAndVariationId(@Param("mappingId") Long mappingId,
                                                                       @Param("variationId") Long variationId);

    /** IMP-08. La variante que es este Producto del POS en esta empresa, con su variación y su producto. */
    @Query("""
            select vm from ProductVariationSyncMapping vm
            join fetch vm.variation v
            join fetch v.product
            join fetch vm.mapping
            where vm.companyId = :companyId and vm.externalVariantId = :externalId
            """)
    Optional<ProductVariationSyncMapping> findByCompanyIdAndExternalId(@Param("companyId") Long companyId,
                                                                      @Param("externalId") Long externalId);

    /** IMP-08. El vínculo de una variación con un Producto del POS de esta empresa. */
    @Query("""
            select vm from ProductVariationSyncMapping vm
            where vm.companyId = :companyId and vm.variation.id = :variationId
            """)
    Optional<ProductVariationSyncMapping> findByCompanyIdAndVariationId(@Param("companyId") Long companyId,
                                                                       @Param("variationId") Long variationId);

    /** IMP-08. Los vínculos de una variación, de cualquier empresa: el panel los muestra y los edita. */
    @Query("select vm from ProductVariationSyncMapping vm where vm.variation.id = :variationId order by vm.id")
    List<ProductVariationSyncMapping> findByVariationId(@Param("variationId") Long variationId);

    /** Las de un lote de mapeos, para la foto de la conciliación. */
    @Query("""
            select vm from ProductVariationSyncMapping vm
            join fetch vm.variation
            where vm.mapping.id in :mappingIds
            order by vm.id
            """)
    List<ProductVariationSyncMapping> findByMappingIdIn(@Param("mappingIds") Collection<Long> mappingIds);
}
