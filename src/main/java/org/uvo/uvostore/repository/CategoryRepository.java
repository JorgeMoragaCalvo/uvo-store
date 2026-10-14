package org.uvo.uvostore.repository;

import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;
import org.uvo.uvostore.entity.catalog.Category;

import java.util.List;
import java.util.Optional;

@Repository
public interface CategoryRepository extends JpaRepository<Category, Long>, JpaSpecificationExecutor<Category> {

    Optional<Category> findById(Long id);
    List<Category> findByParentIdOrderBySortOrderAsc(Long parentId);

    // Tenant-scoped lookups. PROD-17b: las versiones sin tienda (findBySlug, findByName, existsBySlug,
    // las listas de activas/raíz/destacadas) no tenían llamadores y se quitaron.
    Optional<Category> findByStoreIdAndSlug(Long storeId, String slug);
    List<Category> findByStoreIdAndParentIsNullOrderBySortOrderAsc(Long storeId);
    List<Category> findByStoreIdOrderByNameAsc(Long storeId);
    Optional<Category> findByStoreIdAndName(Long storeId, String name); // PosSyncService::getOrCreateCategory()
}
