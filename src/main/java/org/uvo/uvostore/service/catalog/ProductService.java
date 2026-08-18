package org.uvo.uvostore.service.catalog;

import org.uvo.uvostore.entity.catalog.Product;

import java.util.List;

public interface ProductService {

    Product createSimpleProduct(ProductCreateCommand command);
    Product createVariableProduct(ProductCreateCommand command, List<VariationCommand> variations);
    Product updateProduct(Long productId, ProductCreateCommand command);
    void deleteProduct(Long productId);
}
