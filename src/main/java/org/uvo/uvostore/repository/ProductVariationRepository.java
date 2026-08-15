package org.uvo.uvostore.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.uvo.uvostore.entity.catalog.ProductVariation;

import java.util.List;
import java.util.Optional;

@Repository
public interface ProductVariationRepository extends JpaRepository<ProductVariation, Long> {

    List<ProductVariation> findByProductId(Long productId);
    List<ProductVariation> findByProductIdAndActiveTrue(Long productId);
    List<ProductVariation> findByActiveTrueAndStockGreaterThan(int stock);
    Optional<ProductVariation> findBySku(String sku);
}
