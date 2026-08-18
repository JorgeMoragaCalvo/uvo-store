package org.uvo.uvostore.service.catalog;

import org.springframework.web.multipart.MultipartFile;
import org.uvo.uvostore.entity.catalog.Product;
import org.uvo.uvostore.entity.catalog.ProductImage;

public interface ProductImageService {

    ProductImage store(Product product, MultipartFile file, boolean featured);
}
