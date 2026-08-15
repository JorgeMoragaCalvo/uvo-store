package org.uvo.uvostore.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
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

    @Query("""
        SELECT p FROM Product p
        WHERE p.active = true
        AND (LOWER(p.name) LIKE LOWER(CONCAT('%', :term, '%'))
                OR LOWER(p.description) LIKE LOWER(CONCAT('%', :term, '%')))
        """)
    List<Product> search(@Param("term") String term);
}
