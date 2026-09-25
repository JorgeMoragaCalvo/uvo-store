package org.uvo.uvostore.service.order;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.uvo.uvostore.entity.catalog.Product;
import org.uvo.uvostore.entity.catalog.ProductVariation;
import org.uvo.uvostore.entity.order.Coupon;
import org.uvo.uvostore.service.Money;
import org.uvo.uvostore.repository.ProductRepository;
import org.uvo.uvostore.repository.ProductVariationRepository;
import org.uvo.uvostore.repository.SettingRepository;
import org.uvo.uvostore.security.TenantContext;
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
    public CartTotals price(List<CartLineCommand> lines, String couponCode, String region, String commune,
                            Long customerId) {
        Long storeId = TenantContext.requireStoreId();
        BigDecimal subtotalWithTax = BigDecimal.ZERO;
        BigDecimal totalWeight = BigDecimal.ZERO;
        for (CartLineCommand line : lines) {
            BigDecimal unitPrice;
            BigDecimal unitWeight;
            if (line.variationId() != null) {
                ProductVariation variation = variationRepository.findById(line.variationId())
                        .filter(v -> v.getStore().getId().equals(storeId))
                        .orElseThrow(() -> new NoSuchElementException("Variation " + line.variationId() + " not found"));
                unitPrice = variation.getPrice();
                unitWeight = variation.getWeight();
            } else {
                Product product = productRepository.findById(line.productId())
                        .filter(p -> p.getStore().getId().equals(storeId))
                        .orElseThrow(() -> new NoSuchElementException("Product " + line.productId() + " not found"));
                unitPrice = product.getPrice();
                unitWeight = product.getWeight();
            }
            subtotalWithTax = subtotalWithTax.add(unitPrice.multiply(BigDecimal.valueOf(line.quantity())));
            if (unitWeight != null) {
                totalWeight = totalWeight.add(unitWeight.multiply(BigDecimal.valueOf(line.quantity())));
            }
        }

        BigDecimal taxRate = BigDecimal.valueOf(settingRepository.findByStoreIdAndSettingKey(storeId, "tax_rate")
                .map(s -> Double.parseDouble(s.getValue())).orElse(19.0)).divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP);
        boolean pricesIncludeTax = settingRepository.findByStoreIdAndSettingKey(storeId, "prices_include_tax")
                .map(s -> Boolean.parseBoolean(s.getValue())).orElse(false);

        // F06. A partir de aquí todo es dinero en pesos enteros. El redondeo va en el origen y no en
        // la comparación de markPaid: lo que se persiste tiene que ser exactamente lo que una pasarela
        // puede cobrar, y también lo que viaja al POS en el documento tributario.
        //
        // El subtotal primero, para que lo que se derive de él ya sea entero; y el IVA por diferencia
        // en el caso de precios con IVA incluido, no redondeado por su cuenta, para que
        // subtotalSinIVA + IVA sea exactamente el subtotal y no se descuadre por un peso.
        subtotalWithTax = Money.round(subtotalWithTax);

        BigDecimal subtotalWithoutTax;
        BigDecimal taxAmount;
        if (pricesIncludeTax) {
            subtotalWithoutTax = Money.round(subtotalWithTax.divide(BigDecimal.ONE.add(taxRate), 6, RoundingMode.HALF_UP));
            taxAmount = subtotalWithTax.subtract(subtotalWithoutTax);
        } else {
            subtotalWithoutTax = subtotalWithTax;
            taxAmount = Money.round(subtotalWithTax.multiply(taxRate));
        }

        boolean shippingEnabled = settingRepository.findByStoreIdAndSettingKey(storeId, "shipping_enabled")
                .map(s -> Boolean.parseBoolean(s.getValue())).orElse(true);
        Optional<ShippingOption> best = shippingRateService.getBestOption(region, commune, subtotalWithTax, totalWeight);
        // F06: la tarifa por peso y las cotizaciones de los transportistas pueden traer decimales.
        BigDecimal shippingCost = Money.round(best.map(ShippingOption::cost).orElse(BigDecimal.ZERO));
        // A7: the cost alone can't distinguish "free shipping" from "no zone covers this address",
        // and that ambiguity is exactly why every order shipped for $0 — the SPA never sent a
        // region, no zone ever matched, and .orElse(ZERO) made it look deliberate. A store that
        // doesn't ship at all is always "available": there is nothing to quote.
        boolean shippingAvailable = !shippingEnabled || best.isPresent();

        BigDecimal discountAmount = BigDecimal.ZERO;
        boolean couponApplied = false;
        Coupon appliedCoupon = null;
        String customerRejectionReason = null;
        if (couponCode != null && !couponCode.isBlank()) {
            // F05: se valida con el cliente que hace la compra, no con null. Esa validación con null
            // saltaba el límite de usos por cliente, metía el descuento en el total, y el checkout
            // —que sí validaba bien— se limitaba a no adjuntar el cupón. El descuento se quedaba.
            CouponValidationResult result = couponService.validate(couponCode, subtotalWithoutTax, customerId);
            if (result.valid()) {
                // Ya viene redondeado de calculateDiscount, que es también quien calcula el importe de
                // la fila de CouponUsage: si se redondeara aquí y no allí, los informes de cupones
                // dejarían de cuadrar con los totales de las órdenes.
                discountAmount = couponService.calculateDiscount(result.coupon(), subtotalWithoutTax);
                couponApplied = true;
                appliedCoupon = result.coupon();
            } else if (result.customerSpecific()) {
                // El carrito no pudo prever este rechazo (no sabe quién compra), así que ahí se mostró
                // un descuento. Se avisa para que el checkout no cobre en silencio un importe distinto
                // del que el cliente vio.
                customerRejectionReason = result.reason();
            }
        }

        // Todos los sumandos ya son enteros, así que el total lo es sin necesidad de redondearlo — y,
        // lo que importa más, es exactamente la suma de las partes que se guardan con él. Redondear el
        // total por separado habría dejado órdenes donde subtotal + IVA + envío − descuento no da el
        // total, que es lo que descuadra los informes y el documento del POS.
        BigDecimal total = pricesIncludeTax
                ? subtotalWithTax.add(shippingCost).subtract(discountAmount)
                : subtotalWithTax.add(taxAmount).add(shippingCost).subtract(discountAmount);

        return new CartTotals(subtotalWithoutTax, taxAmount, subtotalWithTax, shippingCost, discountAmount, total,
                shippingAvailable, couponApplied, appliedCoupon, customerRejectionReason);
    }
}
