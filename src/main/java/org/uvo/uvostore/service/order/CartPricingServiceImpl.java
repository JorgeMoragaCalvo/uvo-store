package org.uvo.uvostore.service.order;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.uvo.uvostore.entity.catalog.Product;
import org.uvo.uvostore.entity.catalog.ProductVariation;
import org.uvo.uvostore.repository.ProductRepository;
import org.uvo.uvostore.repository.ProductVariationRepository;
import org.uvo.uvostore.repository.SettingRepository;
import org.uvo.uvostore.service.shipping.ShippingOption;
import org.uvo.uvostore.service.shipping.ShippingRateService;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

@Service
public class CartPricingServiceImpl implements CartPricingService {

    private final ProductRepository productRepository;
    private final ProductVariationRepository variationRepository;
    private final SettingRepository settingRepository;
    private final ShippingRateService shippingRateService;
    private final CouponService couponService;

    public CartPricingServiceImpl(
            ProductRepository productRepository,
            ProductVariationRepository variationRepository,
            SettingRepository settingRepository,
            ShippingRateService shippingRateService,
            CouponService couponService) {
        this.productRepository = productRepository;
        this.variationRepository = variationRepository;
        this.settingRepository = settingRepository;
        this.shippingRateService = shippingRateService;
        this.couponService = couponService;
    }

    @Override
    @Transactional(readOnly = true)
    public CartTotals price(List<CartLineCommand> lines, String couponCode, String region, String commune) {
        BigDecimal subtotalWithTax = BigDecimal.ZERO;
        BigDecimal totalWeight = BigDecimal.ZERO;
        for (CartLineCommand line : lines) {
            BigDecimal unitPrice;
            BigDecimal unitWeight;
            if (line.variationId() != null) {
                ProductVariation variation = variationRepository.findById(line.variationId())
                        .orElseThrow(() -> new NoSuchElementException("Variation " + line.variationId() + " not found"));
                unitPrice = variation.getPrice();
                unitWeight = variation.getWeight();
            } else {
                Product product = productRepository.findById(line.productId())
                        .orElseThrow(() -> new NoSuchElementException("Product " + line.productId() + " not found"));
                unitPrice = product.getPrice();
                unitWeight = product.getWeight();
            }
            subtotalWithTax = subtotalWithTax.add(unitPrice.multiply(BigDecimal.valueOf(line.quantity())));
            if (unitWeight != null) {
                totalWeight = totalWeight.add(unitWeight.multiply(BigDecimal.valueOf(line.quantity())));
            }
        }

        BigDecimal taxRate = BigDecimal.valueOf(settingRepository.findBySettingKey("tax_rate")
                .map(s -> Double.parseDouble(s.getValue())).orElse(19.0)).divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP);
        boolean pricesIncludeTax = settingRepository.findBySettingKey("prices_include_tax")
                .map(s -> Boolean.parseBoolean(s.getValue())).orElse(false);

        BigDecimal subtotalWithoutTax;
        BigDecimal taxAmount;
        if (pricesIncludeTax) {
            subtotalWithoutTax = subtotalWithTax.divide(BigDecimal.ONE.add(taxRate), 2, RoundingMode.HALF_UP);
            taxAmount = subtotalWithTax.subtract(subtotalWithoutTax);
        } else {
            subtotalWithoutTax = subtotalWithTax;
            taxAmount = subtotalWithTax.multiply(taxRate).setScale(2, RoundingMode.HALF_UP);
        }

        Optional<ShippingOption> best = shippingRateService.getBestOption(region, commune, subtotalWithTax, totalWeight);
        BigDecimal shippingCost = best.map(ShippingOption::cost).orElse(BigDecimal.ZERO);

        BigDecimal discountAmount = BigDecimal.ZERO;
        if (couponCode != null && !couponCode.isBlank()) {
            CouponValidationResult result = couponService.validate(couponCode, subtotalWithoutTax, null);
            if (result.valid()) {
                discountAmount = couponService.calculateDiscount(result.coupon(), subtotalWithoutTax);
            }
        }

        BigDecimal total = pricesIncludeTax
                ? subtotalWithTax.add(shippingCost).subtract(discountAmount)
                : subtotalWithTax.add(taxAmount).add(shippingCost).subtract(discountAmount);

        return new CartTotals(subtotalWithoutTax, taxAmount, subtotalWithTax, shippingCost, discountAmount, total);
    }
}
