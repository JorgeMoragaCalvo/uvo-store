package org.uvo.uvostore.service.order;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.uvo.uvostore.entity.catalog.Product;
import org.uvo.uvostore.entity.catalog.ProductVariation;
import org.uvo.uvostore.entity.catalog.ProductVariationAttribute;
import org.uvo.uvostore.repository.ProductRepository;
import org.uvo.uvostore.repository.ProductVariationRepository;
import org.uvo.uvostore.repository.SettingRepository;
import org.uvo.uvostore.security.TenantContext;
import org.uvo.uvostore.service.catalog.FileStorageService;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class CartServiceImpl implements CartService {

    private final ProductRepository productRepository;
    private final ProductVariationRepository variationRepository;
    private final CartPricingService cartPricingService;
    private final SettingRepository settingRepository;
    private final FileStorageService fileStorageService;

    public CartServiceImpl(ProductRepository productRepository, ProductVariationRepository variationRepository,
                            CartPricingService cartPricingService, SettingRepository settingRepository,
                            FileStorageService fileStorageService) {
        this.productRepository = productRepository;
        this.variationRepository = variationRepository;
        this.cartPricingService = cartPricingService;
        this.settingRepository = settingRepository;
        this.fileStorageService = fileStorageService;
    }

    @Override
    @Transactional(readOnly = true)
    public CartValidationResult validateItems(List<CartItemCommand> items) {
        Long storeId = TenantContext.requireStoreId();
        List<CartValidatedItemDto> validated = new ArrayList<>();
        Map<String, String> errors = new HashMap<>();

        for (int index = 0; index < items.size(); index++) {
            CartItemCommand item = items.get(index);
            String key = "items." + index;

            if ("variation".equals(item.type())) {
                variationRepository.findById(item.id())
                        .filter(v -> v.getStore().getId().equals(storeId))
                        .ifPresentOrElse(variation -> {
                    if (!variation.isActive() || !variation.getProduct().isActive()) {
                        errors.put(key, "Producto no disponible");
                    } else if (variation.getStock() < item.quantity()) {
                        errors.put(key, "Stock insuficiente. Solo hay " + variation.getStock() + " disponibles");
                    } else {
                        String image = variation.getImage() != null ? fileStorageService.publicUrl(variation.getImage())
                                : featuredImage(variation.getProduct());
                        validated.add(new CartValidatedItemDto(
                                variation.getId(), "variation", variation.getProduct().getName(),
                                attributesOf(variation), variation.getPrice(), variation.getStock(),
                                image, variation.getStock()));
                    }
                }, () -> errors.put(key, "Variación no encontrada"));
            } else {
                productRepository.findById(item.id())
                        .filter(p -> p.getStore().getId().equals(storeId))
                        .ifPresentOrElse(product -> {
                    if (!product.isActive()) {
                        errors.put(key, "Producto no disponible");
                    } else if (product.isManageStock() && product.getStock() < item.quantity()) {
                        errors.put(key, "Stock insuficiente. Solo hay " + product.getStock() + " disponibles");
                    } else {
                        validated.add(new CartValidatedItemDto(
                                product.getId(), "product", product.getName(), null, product.getPrice(),
                                product.getStock(), featuredImage(product),
                                product.isManageStock() ? product.getStock() : 999));
                    }
                }, () -> errors.put(key, "Producto no encontrado"));
            }
        }

        return new CartValidationResult(errors.isEmpty(), validated, errors);
    }

    @Override
    @Transactional(readOnly = true)
    public CartCalculationResult calculate(List<CartItemCommand> items, String region, String commune, String couponCode) {
        List<CartLineCommand> lines = items.stream()
                .map(item -> "variation".equals(item.type())
                        ? new CartLineCommand(null, item.id(), item.quantity())
                        : new CartLineCommand(item.id(), null, item.quantity()))
                .toList();

        CartTotals totals = cartPricingService.price(lines, couponCode, region, commune);

        Long storeId = TenantContext.requireStoreId();
        boolean pricesIncludeTax = settingRepository.findByStoreIdAndSettingKey(storeId, "prices_include_tax")
                .map(s -> Boolean.parseBoolean(s.getValue())).orElse(false);
        BigDecimal taxRate = settingRepository.findByStoreIdAndSettingKey(storeId, "tax_rate")
                .map(s -> new BigDecimal(s.getValue())).orElse(BigDecimal.valueOf(19));
        BigDecimal freeShippingThreshold = settingRepository.findByStoreIdAndSettingKey(storeId, "free_shipping_threshold")
                .map(s -> new BigDecimal(s.getValue())).orElse(BigDecimal.ZERO);
        boolean shippingEnabled = settingRepository.findByStoreIdAndSettingKey(storeId, "shipping_enabled")
                .map(s -> Boolean.parseBoolean(s.getValue())).orElse(true);

        return new CartCalculationResult(
                totals.subtotalWithoutTax(), totals.subtotalWithTax(), totals.shippingCost(), totals.taxAmount(),
                totals.discountAmount(), totals.total(), pricesIncludeTax, taxRate, freeShippingThreshold, shippingEnabled
        );
    }

    private Map<String, String> attributesOf(ProductVariation variation) {
        Map<String, String> attributes = new HashMap<>();
        for (ProductVariationAttribute link : variation.getAttributeAssignments()) {
            attributes.put(link.getAttribute().getName(), link.getAttributeValue().getValue());
        }
        return attributes;
    }

    private String featuredImage(Product product) {
        return product.getProductImages().stream()
                .filter(img -> img.isFeatured())
                .findFirst()
                .or(() -> product.getProductImages().stream().findFirst())
                .map(img -> fileStorageService.publicUrl(img.getImagePath()))
                .orElse(null);
    }
}
