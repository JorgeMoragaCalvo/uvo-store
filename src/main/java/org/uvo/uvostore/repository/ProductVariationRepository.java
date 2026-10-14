package org.uvo.uvostore.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.uvo.uvostore.entity.catalog.ProductVariation;

import java.util.List;

@Repository
public interface ProductVariationRepository extends JpaRepository<ProductVariation, Long> {

    List<ProductVariation> findByProductId(Long productId);
    List<ProductVariation> findByProductIdAndActiveTrue(Long productId);
    // PROD-17b: findByActiveTrueAndStockGreaterThan y findBySku (sin tienda, sin llamadores) se quitaron.

    // C5: same conditional-UPDATE reasoning as ProductRepository.decrementStock — see the comment
    // there. 0 rows affected means the stock wasn't available.
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE ProductVariation v SET v.stock = v.stock - :quantity WHERE v.id = :id AND v.stock >= :quantity")
    int decrementStock(@Param("id") Long id, @Param("quantity") int quantity);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE ProductVariation v SET v.stock = v.stock + :quantity WHERE v.id = :id")
    int restoreStock(@Param("id") Long id, @Param("quantity") int quantity);
}
