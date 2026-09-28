package org.uvo.uvostore.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.uvo.uvostore.entity.catalog.Product;
import org.uvo.uvostore.entity.catalog.enums.ProductType;

import java.util.List;
import java.util.Optional;

@Repository
public interface ProductRepository extends JpaRepository<Product, Long>, JpaSpecificationExecutor<Product> {

    Optional<Product> findBySlug(String slug);
    Optional<Product> findBySku(String sku);
    boolean existsBySku(String sku);

    List<Product> findByActiveTrue();
    List<Product> findByActiveTrueAndStockGreaterThan(int stock);
    List<Product> findByCategoryId(Long categoryId);
    List<Product> findByProductType(ProductType productType);
    List<Product> findByIsFeaturedTrueAndActiveTrue();
    List<Product> findByIsNewTrueAndActiveTrue();
    List<Product> findByIsOnSaleTrueAndActiveTrue();
    Optional<Product> findByPosProductId(String posProductId);

    // Tenant-scoped lookups — the storefront and admin panel always operate within one store.
    Optional<Product> findByStoreIdAndSlug(Long storeId, String slug);
    List<Product> findByStoreIdAndCategoryId(Long storeId, Long categoryId);
    List<Product> findByStoreIdAndIsFeaturedTrueAndActiveTrue(Long storeId);

    @Query("""
        SELECT p FROM Product p
        WHERE p.active = true
        AND (LOWER(p.name) LIKE LOWER(CONCAT('%', :term, '%'))
                OR LOWER(p.description) LIKE LOWER(CONCAT('%', :term, '%')))
        """)
    List<Product> search(@Param("term") String term);

    // C5: the `AND p.stock >= :quantity` is the lock. Reading the stock, comparing it in Java and
    // saving the entity lets two concurrent payments for the last unit both succeed; this pushes
    // the check into the same statement as the write, so the database serialises them. A return
    // value of 0 means the stock wasn't there — the only trustworthy signal, since the entity's
    // in-memory value says nothing about what other transactions did.
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Product p SET p.stock = p.stock - :quantity WHERE p.id = :id AND p.stock >= :quantity")
    int decrementStock(@Param("id") Long id, @Param("quantity") int quantity);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Product p SET p.stock = p.stock + :quantity WHERE p.id = :id")
    int restoreStock(@Param("id") Long id, @Param("quantity") int quantity);

    /**
     * F13. El stock que manda el POS, aplicado <b>solo si nadie lo ha movido</b> desde que el POS lo leyó.
     *
     * <p>El webhook de stock hacía {@code setStock(valorAbsoluto)} + {@code save}: si el POS leía 5,
     * entraba una venta que dejaba 4 y luego llegaba el absoluto 5, el stock volvía a 5 y se podía vender
     * otra vez algo que ya no estaba. La venta sí usa un UPDATE condicional; esto no, así que la carrera
     * la perdía siempre el descuento.
     *
     * <p>La condición es el {@code oldStock} que el POS declara haber visto, y va <b>dentro</b> de la
     * sentencia por el mismo motivo que en {@code decrementStock}: comparar en Java y guardar después no
     * serializa nada. Un retorno de 0 significa "alguien lo movió mientras" — y entonces no se pisa, se
     * informa. Es también lo que descarta un evento viejo reenviado.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Product p SET p.stock = :newStock WHERE p.id = :id AND p.stock = :expectedStock")
    int setStockIfUnchanged(@Param("id") Long id, @Param("expectedStock") int expectedStock,
                            @Param("newStock") int newStock);

    // Stock-only version of ProductVariationServiceImpl.recalculateParentAggregate, for the payment
    // listener. That one also recomputes minPrice and needs TenantContext, neither of which belongs
    // in an AFTER_COMMIT listener — and a stock decrement can't change a variation's price anyway.
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        UPDATE Product p
        SET p.stock = (SELECT COALESCE(SUM(v.stock), 0) FROM ProductVariation v WHERE v.product.id = p.id)
        WHERE p.id = :id
        """)
    int recalculateStockFromVariations(@Param("id") Long id);
}
