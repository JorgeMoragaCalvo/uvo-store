package org.uvo.uvostore.service.catalog;

import org.uvo.uvostore.entity.catalog.Category;

public interface CategoryService {

    Category createCategory(CategoryCommand command);
    Category updateCategory(Long id, CategoryCommand command);
    // CategoryIndex.php::removeImage() is a separate, immediate action in the
    // real component (deletes the file + clears the column right away), distinct from save() —
    // kept as its own method here for the same reason.
    void removeImage(Long id);
    void deleteCategory(Long id);
}
