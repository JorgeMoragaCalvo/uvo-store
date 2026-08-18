package org.uvo.uvostore.service.catalog;

import jakarta.transaction.Transactional;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.uvo.uvostore.entity.catalog.Category;
import org.uvo.uvostore.entity.catalog.Product;
import org.uvo.uvostore.entity.catalog.enums.ProductType;
import org.uvo.uvostore.repository.CategoryRepository;
import org.uvo.uvostore.repository.ProductRepository;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.util.List;
import java.util.NoSuchElementException;

@Service
public class ProductServiceImpl implements ProductService {

    private final ProductRepository productRepository;
    private final CategoryRepository categoryRepository;
    private final ProductImageService productImageService;

    public ProductServiceImpl(
            ProductRepository productRepository,
            CategoryRepository categoryRepository,
            ProductImageService productImageService) {
        this.productRepository = productRepository;
        this.categoryRepository = categoryRepository;
        this.productImageService = productImageService;
    }

    @Override
    @Transactional
    public Product createSimpleProduct(ProductCreateCommand command) {
        Product product = new Product();
        applyCommonFields(product, command);
        product.setProductType(ProductType.SIMPLE);
        product.setSku(command.sku());
        product.setPrice(command.price());
        product.setStock(command.stock() != null ? command.stock() : 0);
        product.setManageStock(command.manageStock());
        Product saved = productRepository.save(product);
        persistImages(saved, command.featuredImage(), command.images());
        return saved;
    }

    @Override
    @Transactional
    public Product createVariableProduct(ProductCreateCommand command, List<VariationCommand> variations) {
        Product product = new Product();
        applyCommonFields(product, command);
        // ProductCreate.php::save() (lines 172-176) — variable products get a synthetic
        // "VAR-XXXXXXXX" SKU and price=0/stock=0/manageStock=false.
        product.setProductType(ProductType.VARIABLE);
        product.setSku("VAR-" + randomSkuSuffix());
        product.setPrice(BigDecimal.ZERO);
        product.setStock(0);
        product.setManageStock(false);
        Product saved = productRepository.save(product);
        persistImages(saved, command.featuredImage(), command.images());
        // NOT wired yet: the `variations` param is accepted by the interface but intentionally
        // unused here. Real Laravel never creates variations in this same call either —
        // ProductCreate.php::save() (lines 209-213) redirects to a SEPARATE variations-management
        // page (ProductVariations.php) after creating just the product shell; variations get
        // added one at a time from there via ProductVariationService (not yet built in this
        // project — no ProductVariationService.java/Impl exists under service/catalog yet).
        // TODO: once ProductVariationService exists, either (a) keep this method shell-only and
        // have callers add variations via ProductVariationService.addVariation() afterward
        // (matches Laravel's actual UX), or (b) loop `variations` here and call
        // ProductVariationService.addVariation(saved.getId(), v) for each — decide based on
        // whether the real REST/SPA flow submits variations in one request or as a follow-up step.
        return saved;
    }

    @Override
    @Transactional
    public Product updateProduct(Long productId, ProductCreateCommand command) {
        Product product = productRepository.findById(productId)
            .orElseThrow(() -> new NoSuchElementException("Product " + productId + " not found"));
        applyCommonFields(product, command);
        if (product.getProductType() == ProductType.SIMPLE) {
            product.setSku(command.sku());
            product.setPrice(command.price());
            product.setStock(command.stock() != null ? command.stock() : 0);
            product.setManageStock(command.manageStock());
        }
        return productRepository.save(product);
    }

    @Override
    @Transactional
    public void deleteProduct(Long productId) {
        productRepository.deleteById(productId);
    }

    // Ports the base $data array ProductCreate.php::save() builds before the simple/variable
    // branch (lines 143-163) — every field there applies to both product types.
    private void applyCommonFields(Product product, ProductCreateCommand command) {
        product.setName(command.name());
        product.setSlug(slugify(command.name()));
        product.setShortDescription(command.shortDescription());
        product.setDescription(command.description());
        product.setPosProductId(command.posProductId());
        product.setActive(command.active());
        product.setFeatured(command.isFeatured());
        product.setMetaTitle(command.metaTitle());
        product.setMetaDescription(command.metaDescription());
        product.setNew(command.isNew());
        product.setNewUntil(command.newUntil());
        product.setSortOrder(command.sortOrder());
        product.setOnSale(command.isOnSale());
        // Straight pass-through, matching Laravel's `$this->sale_price ?: null` etc. (line
        // 159-162) exactly — save() does NOT recompute one from the other server-side; the
        // bidirectional sale-price <-> discount-percentage math (updatedSalePrice()/
        // updatedDiscountPercentage(), lines 100-113) is a client-side live-preview convenience
        // in the Livewire component only, never re-run on submitting. A REST caller (e.g., the SPA
        // admin form) is responsible for keeping the two in sync before it submits, same as
        // Livewire's two-way-bound inputs do today.
        product.setSalePrice(command.salePrice());
        product.setDiscountPercentage(command.discountPercentage());
        product.setSaleStartsAt(command.saleStartsAt());
        product.setSaleEndsAt(command.saleEndsAt());

        if (command.categoryId() != null) {
            Category category = categoryRepository.findById(command.categoryId())
                .orElseThrow(() -> new NoSuchElementException("Category " + command.categoryId() + " not found"));
            product.setCategory(category);
        } else {
            product.setCategory(null);
        }
    }

    // Str::slug($value) equivalent: lowercase, non-alphanumeric runs collapsed to single hyphens,
    // leading/trailing hyphens trimmed. Laravel enforces uniqueness only via a DB-unique
    // validation rule at submitted time (fails on collision) — no retry-with-suffix loop — so the
    // DB unique constraint on Product.slug does the same job here.
    static String slugify(String value) {
        return value.trim().toLowerCase()
            .replaceAll("[^a-z0-9]+", "-")
            .replaceAll("(^-|-$)", "");
    }

    private static String randomSkuSuffix() {
        String chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
        SecureRandom random = new SecureRandom();
        StringBuilder sb = new StringBuilder(8);
        for (int i = 0; i < 8; i++) {
            sb.append(chars.charAt(random.nextInt(chars.length())));
        }
        return sb.toString();
    }

    // Ports ProductCreate.php::save()'s image handling (lines 182-206): featured image first
    // (is_featured=true), then gallery images in submitted order (is_featured=false) —
    // ProductImageService.store() computes sort_order/un-featuring internally.
    private void persistImages(Product product, MultipartFile featuredImage, List<MultipartFile> images) {
        if (featuredImage != null && !featuredImage.isEmpty()) {
            productImageService.store(product, featuredImage, true);
        }
        if (images != null) {
            for (MultipartFile image : images) {
                if (image != null && !image.isEmpty()) {
                    productImageService.store(product, image, false);
                }
            }
        }
    }
}
