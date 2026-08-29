package org.uvo.uvostore.controller.admin.catalog;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.uvo.uvostore.service.catalog.CategoryCommand;
import org.uvo.uvostore.service.catalog.CategoryDto;
import org.uvo.uvostore.service.catalog.CategoryQueryService;
import org.uvo.uvostore.service.catalog.CategoryService;

import java.util.List;

@io.swagger.v3.oas.annotations.tags.Tag(name = "Categorías (admin)", description = "CRUD de categorías, JWT bearer con rol ADMIN")
@io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/api/admin/categories")
public class AdminCategoryController {

    private final CategoryService categoryService;
    private final CategoryQueryService categoryQueryService;

    public AdminCategoryController(CategoryService categoryService, CategoryQueryService categoryQueryService) {
        this.categoryService = categoryService;
        this.categoryQueryService = categoryQueryService;
    }

    @GetMapping
    public List<CategoryDto> index() {
        return categoryQueryService.listAll();
    }

    @GetMapping("/{id}")
    public CategoryDto show(@PathVariable Long id) {
        return categoryQueryService.getById(id);
    }

    @PostMapping(consumes = "multipart/form-data")
    public CategoryDto create(@ModelAttribute CategoryRequest request) {
        var category = categoryService.createCategory(toCommand(request));
        return categoryQueryService.getById(category.getId());
    }

    @PutMapping(value = "/{id}", consumes = "multipart/form-data")
    public CategoryDto update(@PathVariable Long id, @ModelAttribute CategoryRequest request) {
        var category = categoryService.updateCategory(id, toCommand(request));
        return categoryQueryService.getById(category.getId());
    }

    @DeleteMapping("/{id}/image")
    public CategoryDto removeImage(@PathVariable Long id) {
        categoryService.removeImage(id);
        return categoryQueryService.getById(id);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        categoryService.deleteCategory(id);
        return ResponseEntity.noContent().build();
    }

    private CategoryCommand toCommand(CategoryRequest r) {
        return new CategoryCommand(r.name(), r.description(), r.parentId(), r.active(), r.sortOrder(), r.isFeatured(), r.icon(), r.image());
    }
}
