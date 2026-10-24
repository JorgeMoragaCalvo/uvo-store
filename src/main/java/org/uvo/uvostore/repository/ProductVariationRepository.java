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

    // POS-6. Un SKU de variante repetido en la tienda (UNIQUE(store_id, sku)) es un 400 al crearla desde el POS,
    // no un 500 por la restricción.
    boolean existsByStoreIdAndSku(Long storeId, String sku);

    // C5: same conditional-UPDATE reasoning as ProductRepository.decrementStock — see the comment
    // there. 0 rows affected means the stock wasn't available.
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE ProductVariation v SET v.stock = v.stock - :quantity WHERE v.id = :id AND v.stock >= :quantity")
    int decrementStock(@Param("id") Long id, @Param("quantity") int quantity);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE ProductVariation v SET v.stock = v.stock + :quantity WHERE v.id = :id")
    int restoreStock(@Param("id") Long id, @Param("quantity") int quantity);

    /**
     * POS-5. El {@code ProductRepository.setStockIfUnchanged} de una variante (F13): el stock que manda el POS, solo
     * si nadie lo movió desde el valor visto. 0 filas es "alguien lo movió mientras", y entonces no se pisa.
     *
     * <p>Quien la llama bloquea antes el producto padre y después recalcula su stock
     * ({@code recalculateStockFromVariations}): es el orden de cerrojos de todo lo que toca variaciones.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE ProductVariation v SET v.stock = :newStock WHERE v.id = :id AND v.stock = :expectedStock")
    int setStockIfUnchanged(@Param("id") Long id, @Param("expectedStock") int expectedStock,
                            @Param("newStock") int newStock);
}
