package org.uvo.uvostore.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.uvo.uvostore.entity.catalog.Attribute;

import java.util.List;
import java.util.Optional;

@Repository
public interface AttributeRepository extends JpaRepository<Attribute, Long> {

    List<Attribute> findByStoreIdOrderByNameAsc(Long storeId);

    // POS-5. Las variantes del POS traen el atributo por nombre ("Talla"): se reutiliza el de la tienda con ese
    // slug, que es único por tienda (V8).
    Optional<Attribute> findByStoreIdAndSlug(Long storeId, String slug);
}
