package org.uvo.uvostore.service.catalog;

import jakarta.transaction.Transactional;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.uvo.uvostore.entity.catalog.Product;
import org.uvo.uvostore.entity.catalog.ProductImage;
import org.uvo.uvostore.entity.catalog.enums.ImageType;
import org.uvo.uvostore.repository.ProductImageRepository;

import java.util.List;

@Service
public class ProductImageServiceImpl implements ProductImageService {

    private final ProductImageRepository productImageRepository;
    private final FileStorageService fileStorageService;

    public ProductImageServiceImpl(ProductImageRepository productImageRepository, FileStorageService fileStorageService) {
        this.productImageRepository = productImageRepository;
        this.fileStorageService = fileStorageService;
    }

    // Ports Product::addImage() (app/Models/Product.php:302-316): when the new image is featured,
    // un-feature every existing image for this product first (Laravel: images()->update(['featured'
    // => false])); sort_order defaults to max(existing sort_order) + 1. Type is always GALLERY,
    // matching ProductCreate.php::save() passing 'type' => 'gallery' for both the featured shot
    // and the rest.
    @Override
    @Transactional
    public ProductImage store(Product product, MultipartFile file, boolean featured) {
        String path = fileStorageService.store(file, "products");

        List<ProductImage> existing = productImageRepository.findByProductIdOrderBySortOrderAsc(product.getId());
        if (featured) {
            existing.forEach(image -> image.setFeatured(false));
            productImageRepository.saveAll(existing);
        }
        int nextSortOrder = existing.stream().mapToInt(ProductImage::getSortOrder).max().orElse(0) + 1;

        ProductImage image = new ProductImage();
        image.setProduct(product);
        image.setImagePath(path);
        image.setAltText(product.getName());
        image.setSortOrder(nextSortOrder);
        image.setFeatured(featured);
        image.setType(ImageType.GALLERY);
        return productImageRepository.save(image);
    }
}
