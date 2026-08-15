package org.uvo.uvostore.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.uvo.uvostore.entity.catalog.ProductVariationAttribute;

import java.util.List;
import java.util.Optional;

@Repository
public interface ProductVariationAttributeRepository extends JpaRepository<ProductVariationAttribute, Long> {

    List<ProductVariationAttribute> findByVariationId(Long variationId);
    Optional<ProductVariationAttribute> findByVariationIdAndAttributeId(Long variationId, Long attributeId);
}
