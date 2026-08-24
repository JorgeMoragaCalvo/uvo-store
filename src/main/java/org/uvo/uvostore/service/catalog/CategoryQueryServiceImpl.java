package org.uvo.uvostore.service.catalog;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.uvo.uvostore.entity.catalog.Category;
import org.uvo.uvostore.repository.CategoryRepository;
import org.uvo.uvostore.security.TenantContext;

import java.util.Comparator;
import java.util.List;
import java.util.NoSuchElementException;

@Service
public class CategoryQueryServiceImpl implements CategoryQueryService {

    private final CategoryRepository categoryRepository;

    public CategoryQueryServiceImpl(CategoryRepository categoryRepository) {
        this.categoryRepository = categoryRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public List<CategoryDto> rootCategories() {
        return categoryRepository.findByStoreIdAndParentIsNullOrderBySortOrderAsc(TenantContext.requireStoreId()).stream()
                .filter(Category::isActive)
                .sorted(Comparator.comparingInt(Category::getSortOrder).thenComparing(Category::getName))
                .map(CategoryDtoMapper::toDto)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public CategoryDto getBySlug(String slug) {
        Category category = categoryRepository.findByStoreIdAndSlug(TenantContext.requireStoreId(), slug)
                .filter(Category::isActive)
                .orElseThrow(() -> new NoSuchElementException("Category " + slug + " not found"));
        return CategoryDtoMapper.toDto(category);
    }

    @Override
    @Transactional(readOnly = true)
    public List<CategoryDto> listAll() {
        return categoryRepository.findByStoreIdOrderByNameAsc(TenantContext.requireStoreId()).stream()
                .map(CategoryDtoMapper::toDto)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public CategoryDto getById(Long id) {
        Category category = categoryRepository.findById(id)
                .filter(c -> c.getStore().getId().equals(TenantContext.requireStoreId()))
                .orElseThrow(() -> new NoSuchElementException("Category " + id + " not found"));
        return CategoryDtoMapper.toDto(category);
    }
}
