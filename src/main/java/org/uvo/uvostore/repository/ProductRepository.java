package org.uvo.uvostore.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.uvo.uvostore.entity.catalog.Product;
import org.uvo.uvostore.entity.catalog.enums.ProductType;

import java.util.List;
import java.util.Optional;

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
}
