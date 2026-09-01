package org.uvo.uvostore.service.catalog;

import org.springframework.web.multipart.MultipartFile;
import org.uvo.uvostore.entity.catalog.Product;
import org.uvo.uvostore.entity.catalog.ProductImage;

public interface ProductImageService {

    ProductImage store(Product product, MultipartFile file, boolean featured);

    /**
     * Deletes one image of a product, its stored file included. If it was the featured one, the
     * next image by sort order takes over, so a product is never left with a gallery but no
     * thumbnail for the admin listing.
     */
    void removeImage(Long productId, Long imageId);
}
