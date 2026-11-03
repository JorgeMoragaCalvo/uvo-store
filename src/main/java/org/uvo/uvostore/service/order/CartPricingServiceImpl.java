package org.uvo.uvostore.service.order;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.uvo.uvostore.entity.catalog.Product;
import org.uvo.uvostore.entity.catalog.ProductVariation;
import org.uvo.uvostore.entity.catalog.enums.ProductType;
import org.uvo.uvostore.entity.order.Coupon;
import org.uvo.uvostore.entity.settings.Setting;
import org.uvo.uvostore.service.BusinessException;
import org.uvo.uvostore.service.Money;
import org.uvo.uvostore.service.settings.SettingValues;
import org.uvo.uvostore.repository.ProductRepository;
import org.uvo.uvostore.repository.ProductVariationRepository;
import org.uvo.uvostore.repository.SettingRepository;
import org.uvo.uvostore.service.catalog.EffectivePrice;
import org.uvo.uvostore.security.TenantContext;
import org.uvo.uvostore.service.shipping.ShippingOption;
import org.uvo.uvostore.service.shipping.ShippingRateService;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
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
        List<BigDecimal> lineSubtotals = new ArrayList<>(lines.size());
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
                // F09: aquí también, y no solo en la validación del carrito, porque /cart/calculate
                // entra directo a este cálculo sin pasar por validateItems. Cotizar la ficha padre
                // devolvería el precio de la variante más barata —o 0 si aún no tiene variaciones—
                // como si fuera comprable.
                requireSimpleProduct(product);
                // F11: el precio vigente, no el de lista. Es la misma función que usa el catálogo, así
                // que lo que se cotiza es lo que la ficha mostró.
                unitPrice = EffectivePrice.of(product);
                unitWeight = product.getWeight();
            }
            BigDecimal lineSubtotal = unitPrice.multiply(BigDecimal.valueOf(line.quantity()));
            lineSubtotals.add(lineSubtotal);
            subtotalWithTax = subtotalWithTax.add(lineSubtotal);
            if (unitWeight != null) {
                totalWeight = totalWeight.add(unitWeight.multiply(BigDecimal.valueOf(line.quantity())));
            }
        }

        // F17: el mismo lector que usan /cart/calculate y /checkout/config. Antes aquí había
        // Double.parseDouble y allí new BigDecimal, que no aceptan el mismo texto: con
        // tax_rate=" 19 " esta línea cobraba bien y las otras dos respondían 400. Y un valor no
        // numérico guardado ya no llega como NumberFormatException con el mensaje interno de
        // BigDecimal: SettingValues avisa a Sentry y responde algo accionable.
        BigDecimal taxRate = decimalSetting(storeId, "tax_rate", BigDecimal.valueOf(19))
                .divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP);
        boolean pricesIncludeTax = settingRepository.findByStoreIdAndSettingKey(storeId, "prices_include_tax")
                .map(s -> Boolean.parseBoolean(s.getValue())).orElse(false);

        // F06. A partir de aquí todo es dinero en pesos enteros. El redondeo va en el origen y no en
        // la comparación de markPaid: lo que se persiste tiene que ser exactamente lo que una pasarela
        // puede cobrar, y también lo que viaja al POS en el documento tributario.
        //
        // El subtotal primero, para que lo que se derive de él ya sea entero. El IVA se calcula más abajo,
        // después del cupón (GAP-05).
        subtotalWithTax = Money.round(subtotalWithTax);

        BigDecimal subtotalWithoutTax = pricesIncludeTax
                ? Money.round(subtotalWithTax.divide(BigDecimal.ONE.add(taxRate), 6, RoundingMode.HALF_UP))
                : subtotalWithTax;

        boolean shippingEnabled = settingRepository.findByStoreIdAndSettingKey(storeId, "shipping_enabled")
                .map(s -> Boolean.parseBoolean(s.getValue())).orElse(true);
        Optional<ShippingOption> best = shippingRateService.getBestOption(region, commune, subtotalWithTax, totalWeight);
        // F06: la tarifa por peso y las cotizaciones de los transportistas pueden traer decimales.
        BigDecimal shippingCost = Money.round(best.map(ShippingOption::cost).orElse(BigDecimal.ZERO));
        // El envío gratis de la configuración general se anunciaba en el encabezado de la tienda y no se
        // aplicaba: el cobro salía solo de las tarifas. Mismas condiciones que el anuncio (Header.tsx), y
        // contra el mismo monto que el "envío gratis desde" de cada tarifa, que es el subtotal antes del
        // cupón. Exige una opción de envío: lo gratis no da cobertura a una dirección que no la tiene (A7).
        if (shippingEnabled && best.isPresent() && freeShippingReached(storeId, subtotalWithTax)) {
            shippingCost = BigDecimal.ZERO;
        }
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
                // Ya viene redondeado de calculateDiscount. La fila de CouponUsage copia este mismo importe
                // de la orden (GAP-05), así que los informes de cupones cuadran con los totales.
                //
                // La base es el subtotal sin IVA también con precios con IVA incluido: el 10 % de un
                // precio de 11.900 descuenta 1.000. Es el criterio de siempre y GAP-05 lo mantiene; lo
                // que corrige es el IVA que se calcula después.
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

        // GAP-05. El IVA va sobre lo que de verdad se paga, después del cupón. Antes se calculaba sobre el
        // subtotal sin descontar: con precios sin IVA, 10.000 con un cupón de 1.000 cobraba 10.900 en vez de
        // 10.710, y con precios con IVA la orden y el documento POS declaraban un IVA de 1.900 cuando el
        // contenido era 1.740. El envío sigue fuera de la base, como estaba.
        //
        // Todos los sumandos son enteros, así que el total lo es sin redondearlo, y es exactamente la suma
        // de las partes que se guardan con él. Con IVA incluido, el IVA sale por diferencia y no redondeado
        // por su cuenta, para que neto + IVA sea exactamente lo pagado (F06).
        BigDecimal taxAmount;
        BigDecimal total;
        if (pricesIncludeTax) {
            BigDecimal paid = subtotalWithTax.subtract(discountAmount);
            taxAmount = paid.subtract(Money.round(paid.divide(BigDecimal.ONE.add(taxRate), 6, RoundingMode.HALF_UP)));
            total = paid.add(shippingCost);
        } else {
            BigDecimal taxable = subtotalWithoutTax.subtract(discountAmount);
            taxAmount = Money.round(taxable.multiply(taxRate));
            total = taxable.add(taxAmount).add(shippingCost);
        }

        return new CartTotals(subtotalWithoutTax, taxAmount, subtotalWithTax, shippingCost, discountAmount, total,
                shippingAvailable, couponApplied, appliedCoupon, customerRejectionReason,
                best.orElse(null), pricesIncludeTax, lineAmounts(lineSubtotals, discountAmount, taxAmount));
    }

    /**
     * GAP-05. El descuento se reparte en proporción al subtotal de cada línea, y el IVA en proporción a lo que
     * queda de cada una después de su descuento, que es su base. Así las líneas suman exactamente el descuento y
     * el IVA de la orden, y el documento POS cuadra línea por línea.
     */
    private static List<LineAmounts> lineAmounts(List<BigDecimal> subtotals, BigDecimal discount, BigDecimal tax) {
        List<BigDecimal> discounts = AmountSpread.spread(discount, subtotals);
        List<BigDecimal> bases = new ArrayList<>(subtotals.size());
        for (int i = 0; i < subtotals.size(); i++) {
            bases.add(subtotals.get(i).subtract(discounts.get(i)));
        }
        List<BigDecimal> taxes = AmountSpread.spread(tax, bases);
        List<LineAmounts> result = new ArrayList<>(subtotals.size());
        for (int i = 0; i < subtotals.size(); i++) {
            result.add(new LineAmounts(subtotals.get(i), discounts.get(i), taxes.get(i)));
        }
        return result;
    }

    private boolean freeShippingReached(Long storeId, BigDecimal orderAmount) {
        boolean enabled = settingRepository.findByStoreIdAndSettingKey(storeId, "free_shipping_enabled")
                .map(s -> Boolean.parseBoolean(s.getValue())).orElse(false);
        if (!enabled) {
            return false;
        }
        BigDecimal threshold = decimalSetting(storeId, "free_shipping_threshold", BigDecimal.ZERO);
        return threshold.signum() > 0 && orderAmount.compareTo(threshold) >= 0;
    }

    private BigDecimal decimalSetting(Long storeId, String key, BigDecimal fallback) {
        return SettingValues.decimal(key,
                settingRepository.findByStoreIdAndSettingKey(storeId, key).map(Setting::getValue).orElse(null),
                fallback);
    }

    /**
     * F09. Una línea sin {@code variationId} tiene que referirse a un producto simple.
     *
     * <p>La ficha padre de un producto variable no es un artículo: no tiene talla ni color, su precio es
     * el de la variación más barata —o cero mientras no tenga ninguna— y arrastra
     * {@code manageStock = false} de por vida, así que ni el stock la frena.
     *
     * <p>Está duplicado a propósito con el mensaje por línea de {@code CartServiceImpl.validateItems}:
     * ese es el que ve el comprador, y este es el que impide que el importe se calcule siquiera. La
     * solución de fondo —un único resolutor de línea compartido por validación, cálculo y checkout— es
     * un refactor de los tres servicios de dinero a la vez, y no toca hacerlo dentro de este arreglo.
     */
    static void requireSimpleProduct(Product product) {
        if (product.getProductType() != ProductType.SIMPLE) {
            throw new BusinessException("Elige una variante de este producto");
        }
    }
}
