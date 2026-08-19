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

    Optional<Category> findBySlug(String slug);
    Optional<Category> findByName(String name); // PosSyncService::getOrCreateCategory() lookup key
    Optional<Category> findById(Long id);
    List<Category> findByActiveTrueOrderBySortOrderAsc();
    List<Category> findByParentIsNullOrderBySortOrderAsc(); // Category::rootCategories
    List<Category> findByParentIdOrderBySortOrderAsc(Long parentId);
    List<Category> findByIsFeaturedTrueAndActiveTrue();
    boolean existsBySlug(String slug);
}
