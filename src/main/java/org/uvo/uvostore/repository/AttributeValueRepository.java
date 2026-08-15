package org.uvo.uvostore.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.uvo.uvostore.entity.catalog.AttributeValue;

import java.util.List;
import java.util.Optional;

public interface AttributeValueRepository extends JpaRepository<AttributeValue, Long> {

    List<AttributeValue> finByAttributeIdOrderBySortOrderAsc(Long attributeId);
    Optional<AttributeValue> findByAttributeIdAndSlug(Long attributeId, String slug);
}
