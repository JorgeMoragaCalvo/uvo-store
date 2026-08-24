package org.uvo.uvostore.service.catalog;

import jakarta.transaction.Transactional;
import org.springframework.stereotype.Service;
import org.uvo.uvostore.entity.catalog.Category;
import org.uvo.uvostore.repository.CategoryRepository;
import org.uvo.uvostore.security.TenantContext;

import java.util.NoSuchElementException;

@Service
public class CategoryServiceImpl implements CategoryService{

    private final CategoryRepository categoryRepository;
    private final FileStorageService fileStorageService;

    public CategoryServiceImpl(CategoryRepository categoryRepository, FileStorageService fileStorageService) {
        this.categoryRepository = categoryRepository;
        this.fileStorageService = fileStorageService;
    }

    @Override
    @Transactional
    public Category createCategory(CategoryCommand command) {
        Category category = new Category();
        category.setStore(TenantContext.requireCurrent());
        applyCommonFields(category, command);
        if (command.image() != null && !command.image().isEmpty()) {
            category.setImage(fileStorageService.store(command.image(), "categories"));
        }
        return categoryRepository.save(category);
    }

    @Override
    @Transactional
    public Category updateCategory(Long id, CategoryCommand command) {
        Category category = categoryRepository.findById(id)
            .filter(c -> c.getStore().getId().equals(TenantContext.requireStoreId()))
            .orElseThrow(() -> new NoSuchElementException("Category " + id + " not found"));
        applyCommonFields(category, command);
        if (command.image() != null && !command.image().isEmpty()) {
            if (category.getImage() != null) {
                fileStorageService.delete(category.getImage());
            }
            category.setImage(fileStorageService.store(command.image(), "categories"));
        }
        return categoryRepository.save(category);
    }

    @Override
    @Transactional
    public void removeImage(Long id) {
        Category category = categoryRepository.findById(id)
            .filter(c -> c.getStore().getId().equals(TenantContext.requireStoreId()))
            .orElseThrow(() -> new NoSuchElementException("Category " + id + " not found"));
        if (category.getImage() != null) {
            fileStorageService.delete(category.getImage());
            category.setImage(null);
            categoryRepository.save(category);
        }
    }

    @Override
    @Transactional
    public void deleteCategory(Long id) {
        Category category = categoryRepository.findById(id)
                        .filter(c -> c.getStore().getId().equals(TenantContext.requireStoreId()))
                        .orElseThrow(() -> new NoSuchElementException("Category " + id + " not found"));

        if (!category.getChildren().isEmpty()) {
            throw new IllegalStateException("Cannot delete category with subcategories.");
        }
        if (!category.getProducts().isEmpty()) {
            throw new IllegalStateException("Cannot delete category with products.");
        }
        if (category.getImage() != null) {
            fileStorageService.delete(category.getImage());
        }
        categoryRepository.deleteById(id);
    }

    private void applyCommonFields(Category category, CategoryCommand command) {
        category.setName(command.name());
        category.setSlug(ProductServiceImpl.slugify(command.name()));
        category.setDescription(command.description());
        category.setActive(command.active());
        category.setSortOrder(command.sortOrder());
        category.setFeatured(command.isFeatured());
        category.setIcon(command.icon());
        if (command.parentId() != null) {
            Category parent = categoryRepository.findById(command.parentId())
                .filter(c -> c.getStore().getId().equals(TenantContext.requireStoreId()))
                .orElseThrow(() -> new NoSuchElementException("Category " + command.parentId() + " not found"));
            category.setParent(parent);
        } else {
            category.setParent(null);
        }
    }
}
