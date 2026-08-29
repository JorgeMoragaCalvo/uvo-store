package org.uvo.uvostore.controller.catalog;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.uvo.uvostore.service.catalog.CategoryDto;
import org.uvo.uvostore.service.catalog.CategoryQueryService;

import java.util.List;

@io.swagger.v3.oas.annotations.tags.Tag(name = "Categorías (público)", description = "Categorías visibles en el storefront, sin autenticación")
@RestController
@RequestMapping("/api/v1/categories")
public class CategoryController {

    private final CategoryQueryService categoryQueryService;

    public CategoryController(CategoryQueryService categoryQueryService) {
        this.categoryQueryService = categoryQueryService;
    }

    @GetMapping
    public List<CategoryDto> index() {
        return categoryQueryService.rootCategories();
    }

    @GetMapping("/{slug}")
    public CategoryDto show(@PathVariable String slug) {
        return categoryQueryService.getBySlug(slug);
    }
}
