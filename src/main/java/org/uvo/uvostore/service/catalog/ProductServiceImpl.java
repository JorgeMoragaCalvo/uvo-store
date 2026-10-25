package org.uvo.uvostore.service.catalog;

import jakarta.transaction.Transactional;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.uvo.uvostore.entity.audit.enums.AuditAction;
import org.uvo.uvostore.entity.audit.enums.AuditTargetType;
import org.uvo.uvostore.entity.catalog.Category;
import org.uvo.uvostore.entity.catalog.Product;
import org.uvo.uvostore.entity.catalog.enums.ProductType;
import org.uvo.uvostore.repository.CategoryRepository;
import org.uvo.uvostore.repository.ProductRepository;
import org.uvo.uvostore.security.TenantContext;
import org.uvo.uvostore.service.audit.AuditDiff;
import org.uvo.uvostore.service.audit.AuditEntry;
import org.uvo.uvostore.service.audit.AuditTrail;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;

@Service
public class ProductServiceImpl implements ProductService {

    private final ProductRepository productRepository;
    private final CategoryRepository categoryRepository;
    private final ProductImageService productImageService;
    private final PosCatalogOwnership posOwnership;
    private final AuditTrail auditTrail;

    public ProductServiceImpl(
            ProductRepository productRepository,
            CategoryRepository categoryRepository,
            ProductImageService productImageService,
            PosCatalogOwnership posOwnership,
            AuditTrail auditTrail) {
        this.productRepository = productRepository;
        this.categoryRepository = categoryRepository;
        this.productImageService = productImageService;
        this.posOwnership = posOwnership;
        this.auditTrail = auditTrail;
    }

    @Override
    @Transactional
    public Product createSimpleProduct(ProductCreateCommand command) {
        Product product = new Product();
        product.setStore(TenantContext.requireCurrent());
        // Un producto nuevo no tiene mapeo: nada es del POS todavía.
        applyCommonFields(product, command, EnumSet.noneOf(PosOwnedField.class));
        product.setProductType(ProductType.SIMPLE);
        product.setSku(command.sku());
        product.setPrice(command.price());
        product.setStock(command.stock() != null ? command.stock() : 0);
        product.setManageStock(command.manageStock() == null || command.manageStock());
        Product saved = productRepository.save(product);
        persistImages(saved, command.featuredImage(), command.images());
        return saved;
    }

    @Override
    @Transactional
    public Product createVariableProduct(ProductCreateCommand command, List<VariationCommand> variations) {
        Product product = new Product();
        product.setStore(TenantContext.requireCurrent());
        applyCommonFields(product, command, EnumSet.noneOf(PosOwnedField.class));
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
            .filter(p -> p.getStore().getId().equals(TenantContext.requireStoreId()))
            .orElseThrow(() -> new NoSuchElementException("Product " + productId + " not found"));
        // POS-2: antes de tocar nada. Si rechazara después, el producto ya estaría cambiado en la sesión (y la
        // consulta de mapeos lo volcaría) y las imágenes de abajo ya estarían en disco.
        Set<PosOwnedField> posOwned = posOwnership.checkUpdate(product, command);
        Map<String, Object> pricesBefore = priceFields(product);
        applyCommonFields(product, command, posOwned);
        if (product.getProductType() == ProductType.SIMPLE) {
            if (!posOwned.contains(PosOwnedField.SKU)) {
                product.setSku(command.sku());
            }
            if (!posOwned.contains(PosOwnedField.PRICE)) {
                product.setPrice(command.price());
            }
            if (!posOwned.contains(PosOwnedField.STOCK)) {
                product.setStock(command.stock() != null ? command.stock() : 0);
            }
            if (!posOwned.contains(PosOwnedField.MANAGE_STOCK) && command.manageStock() != null) {
                product.setManageStock(command.manageStock());
            }
        }
        Product saved = productRepository.save(product);
        // PROD-18. Solo el precio: el resto de la ficha no cambia cuánto se cobra. Los cambios que manda el POS
        // (/api/sync, webhooks) no pasan por aquí y no se auditan: ya quedan en sync_webhook_logs.
        AuditDiff prices = AuditDiff.create().values(pricesBefore, priceFields(saved));
        if (prices.hasChanges()) {
            auditTrail.record(AuditEntry.of(AuditAction.PRODUCT_PRICE_UPDATE, AuditTargetType.PRODUCT, saved.getId())
                    .details(prices.with("sku", saved.getSku())));
        }
        // The update path used to drop featuredImage/images on the floor even though ProductRequest
        // accepted them, so the admin panel had no way to add or change a photo after creation —
        // the only fix was deleting the product and making it again. persistImages skips null/empty
        // files, so saving without touching the file inputs still changes nothing. It appends
        // rather than replaces: a new featured image un-features the previous one (see
        // ProductImageServiceImpl.store), and removing one is ProductImageService.removeImage's job.
        persistImages(saved, command.featuredImage(), command.images());
        return saved;
    }

    @Override
    @Transactional
    public void deleteProduct(Long productId) {
        Product product = productRepository.findById(productId)
                .filter(p -> p.getStore().getId().equals(TenantContext.requireStoreId()))
                .orElseThrow(() -> new NoSuchElementException("Product " + productId + " not found"));
        productRepository.delete(product);
    }

    @Override
    @Transactional
    public Product toggleActive(Long productId) {
        Product product = productRepository.findById(productId)
                .filter(p -> p.getStore().getId().equals(TenantContext.requireStoreId()))
                .orElseThrow(() -> new NoSuchElementException("Product " + productId + " not found"));
        product.setActive(!product.isActive());
        return productRepository.save(product);
    }

    @Override
    @Transactional
    public Product toggleFeatured(Long productId) {
        Product product = productRepository.findById(productId)
                .filter(p -> p.getStore().getId().equals(TenantContext.requireStoreId()))
                .orElseThrow(() -> new NoSuchElementException("Product " + productId + " not found"));
        product.setFeatured(!product.isFeatured());
        return productRepository.save(product);
    }

    // PROD-18. Lo que decide el precio que se cobra, oferta incluida.
    private static Map<String, Object> priceFields(Product product) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("price", product.getPrice());
        fields.put("salePrice", product.getSalePrice());
        fields.put("discountPercentage", product.getDiscountPercentage());
        fields.put("onSale", product.isOnSale());
        fields.put("saleStartsAt", product.getSaleStartsAt());
        fields.put("saleEndsAt", product.getSaleEndsAt());
        return fields;
    }

    // Ports the base $data array ProductCreate.php::save() builds before the simple/variable
    // branch (lines 143-163) — every field there applies to both product types.
    //
    // POS-2: lo que está en posOwned lo escribe UvoPOS y aquí se deja como está. Con el nombre bloqueado
    // tampoco se recalcula el slug, que el webhook deriva del nombre.
    private void applyCommonFields(Product product, ProductCreateCommand command, Set<PosOwnedField> posOwned) {
        if (!posOwned.contains(PosOwnedField.NAME)) {
            product.setName(command.name());
            product.setSlug(slugify(command.name()));
        }
        product.setShortDescription(command.shortDescription());
        if (!posOwned.contains(PosOwnedField.DESCRIPTION)) {
            product.setDescription(command.description());
        }
        // POS-2: el formulario no lo envía, así que cada guardado lo borraba. Es identidad del POS.
        if (command.posProductId() != null) {
            product.setPosProductId(command.posProductId());
        }
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
                .filter(c -> c.getStore().getId().equals(TenantContext.requireStoreId()))
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
