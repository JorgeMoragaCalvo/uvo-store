package org.uvo.uvostore.service.catalog;

import jakarta.transaction.Transactional;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.uvo.uvostore.entity.catalog.Product;
import org.uvo.uvostore.entity.catalog.ProductImage;
import org.uvo.uvostore.entity.catalog.enums.ImageType;
import org.uvo.uvostore.repository.ProductImageRepository;
import org.uvo.uvostore.security.TenantContext;

import java.util.Comparator;
import java.util.List;
import java.util.NoSuchElementException;

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
        image.setStore(TenantContext.requireCurrent());
        image.setProduct(product);
        image.setImagePath(path);
        image.setAltText(product.getName());
        image.setSortOrder(nextSortOrder);
        image.setFeatured(featured);
        image.setType(ImageType.GALLERY);
        ProductImage saved = productImageRepository.save(image);

        // Keep the owning side in sync. ProductDtoMapper builds the response from
        // product.getProductImages(), so without this the caller gets a product with an empty
        // `images` array right after uploading one — the row is in the database, but the entity
        // already in the persistence context never learns about it.
        product.getProductImages().add(saved);
        return saved;
    }

    // Mirrors CategoryServiceImpl.removeImage: the stored file goes with the row, so deleting a
    // photo doesn't leave an orphan in uploads/products/ forever. Scoped by product AND store —
    // an image belonging to another tenant must read as "not found", never be deletable.
    @Override
    @Transactional
    public void removeImage(Long productId, Long imageId) {
        ProductImage image = productImageRepository.findById(imageId)
                .filter(i -> i.getProduct().getId().equals(productId))
                .filter(i -> i.getStore().getId().equals(TenantContext.requireStoreId()))
                .orElseThrow(() -> new NoSuchElementException("Product image " + imageId + " not found"));

        boolean wasFeatured = image.isFeatured();
        Product product = image.getProduct();

        fileStorageService.delete(image.getImagePath());
        product.getProductImages().removeIf(i -> i.getId().equals(imageId));
        productImageRepository.delete(image);

        // Without this the product keeps a gallery but loses its thumbnail: the admin listing
        // renders ProductDto.featuredImage, which is derived from whichever image is flagged
        // featured. Works off the in-memory collection so the DTO built right after this call
        // reflects the promotion.
        if (wasFeatured) {
            product.getProductImages().stream()
                    .min(Comparator.comparingInt(ProductImage::getSortOrder))
                    .ifPresent(promoted -> {
                        promoted.setFeatured(true);
                        productImageRepository.save(promoted);
                    });
        }
    }
}
