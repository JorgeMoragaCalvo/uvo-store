package org.uvo.uvostore.service.shipping;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.uvo.uvostore.entity.catalog.Product;
import org.uvo.uvostore.entity.catalog.ProductVariation;
import org.uvo.uvostore.entity.settings.Setting;
import org.uvo.uvostore.entity.shipping.ShippingMethod;
import org.uvo.uvostore.entity.shipping.ShippingRate;
import org.uvo.uvostore.entity.shipping.ShippingZone;
import org.uvo.uvostore.entity.shipping.enums.ShippingMethodType;
import org.uvo.uvostore.repository.ProductRepository;
import org.uvo.uvostore.repository.ProductVariationRepository;
import org.uvo.uvostore.repository.SettingRepository;
import org.uvo.uvostore.repository.ShippingMethodRepository;
import org.uvo.uvostore.repository.ShippingRateRepository;
import org.uvo.uvostore.repository.ShippingZoneRepository;
import org.uvo.uvostore.security.TenantContext;
import org.uvo.uvostore.service.Money;
import org.uvo.uvostore.service.geo.GeoCatalog;
import org.uvo.uvostore.service.order.CartLineCommand;
import org.uvo.uvostore.service.shipping.carrier.ShippingCarrierPolicy;
import org.uvo.uvostore.service.shipping.carrier.ShippingCarrierQuoteDispatcher;
import org.uvo.uvostore.service.shipping.carrier.ShippingCarrierQuoteRequest;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;

@Service
public class ShippingRateServiceImpl implements ShippingRateService {

    private final ShippingZoneRepository zoneRepository;
    private final ShippingRateRepository rateRepository;
    private final ShippingMethodRepository methodRepository;
    private final ProductRepository productRepository;
    private final ProductVariationRepository variationRepository;
    private final SettingRepository settingRepository;
    private final ShippingCarrierQuoteDispatcher carrierQuoteDispatcher;
    private final ShippingCarrierPolicy carrierPolicy;
    private final GeoCatalog geoCatalog;
    private final ZoneGeography zoneGeography;

    public ShippingRateServiceImpl(
            ShippingZoneRepository zoneRepository,
            ShippingRateRepository rateRepository,
            ShippingMethodRepository methodRepository,
            ProductRepository productRepository,
            ProductVariationRepository variationRepository,
            SettingRepository settingRepository,
            ShippingCarrierQuoteDispatcher carrierQuoteDispatcher,
            ShippingCarrierPolicy carrierPolicy,
            GeoCatalog geoCatalog,
            ZoneGeography zoneGeography) {
        this.zoneRepository = zoneRepository;
        this.rateRepository = rateRepository;
        this.methodRepository = methodRepository;
        this.productRepository = productRepository;
        this.variationRepository = variationRepository;
        this.settingRepository = settingRepository;
        this.carrierQuoteDispatcher = carrierQuoteDispatcher;
        this.carrierPolicy = carrierPolicy;
        this.geoCatalog = geoCatalog;
        this.zoneGeography = zoneGeography;
    }

    @Override
    @Transactional(readOnly = true)
    public List<ShippingOption> getAvailableOptions(String region, String commune, BigDecimal orderAmount, BigDecimal totalWeight) {
        List<ShippingOption> options = new ArrayList<>();

        // Las cotizaciones de transportista reciben los nombres canónicos cuando los hay, igual que la zona.
        String canonicalRegion = geoCatalog.resolveRegion(region).orElse(region);
        String canonicalCommune = geoCatalog.resolveCommune(region, commune).orElse(commune);
        ShippingZone zone = findZone(region, commune);
        if (zone != null) {
            for (ShippingRate rate : rateRepository.findByZoneIdAndIsActiveTrue(zone.getId())) {
                // F16: findByZoneIdAndIsActiveTrue filtra el flag de la TARIFA, no el del MÉTODO, así que
                // desactivar un método de envío no lo quitaba de las cotizaciones: se seguía ofreciendo y
                // cobrando su tarifa. Y era una incoherencia dentro de este mismo método — la vía de
                // transportistas de abajo (carrierQuotes) sí filtra por isActive desde el principio.
                if (!rate.getMethod().isActive()) continue;
                if (!isApplicable(rate, orderAmount, totalWeight)) continue;
                BigDecimal cost = calculateCost(rate, orderAmount, totalWeight);
                if (cost.signum() < 0) continue;
                options.add(new ShippingOption(
                        rate.getMethod().getId(),
                        rate.getMethod().getName(),
                        cost,
                        deliveryTimeString(rate.getMethod()),
                        cost.signum() == 0,
                        // F16: con qué tarifa se calculó, para que la orden lo guarde.
                        rate.getId()
                ));
            }
        }

        options.addAll(carrierQuotes(canonicalRegion, canonicalCommune, orderAmount, totalWeight));

        options.sort(Comparator.comparing(ShippingOption::cost));
        return options;
    }

    // Fase 3: methods with hasApiIntegration=true bypass the static ShippingRate table entirely —
    // their cost comes from a live carrier quote instead. A carrier that fails to quote (no
    // credentials, network error, unexpected response) is simply omitted, never breaks checkout.
    private List<ShippingOption> carrierQuotes(String region, String commune, BigDecimal orderAmount, BigDecimal totalWeight) {
        Long storeId = TenantContext.requireStoreId();
        List<ShippingMethod> apiMethods = methodRepository.findByStoreId(storeId).stream()
                .filter(ShippingMethod::isActive)
                .filter(ShippingMethod::isHasApiIntegration)
                .filter(m -> m.getCarrier() != null)
                // PROD-12. Cinturón: la administración ya rechaza configurar o reactivar un transportista
                // no disponible, pero las filas escritas antes de ese guarda siguen en la base y este es el
                // único camino por el que cotizarían. Con esto, "no se cobra ningún envío con un
                // transportista sin homologar" es cierto por cualquier camino, no solo por el formulario.
                .filter(m -> carrierPolicy.isAvailable(m.getCarrier()))
                .toList();
        if (apiMethods.isEmpty()) return List.of();

        String originRegion = storeSetting("shipping_origin_region");
        String originCommune = storeSetting("shipping_origin_commune");
        ShippingCarrierQuoteRequest request = new ShippingCarrierQuoteRequest(
                originRegion, originCommune, region, commune, totalWeight, orderAmount);

        List<ShippingOption> quotes = new ArrayList<>();
        for (ShippingMethod method : apiMethods) {
            carrierQuoteDispatcher.quote(method, request).ifPresent(quotes::add);
        }
        return quotes;
    }

    private String storeSetting(String key) {
        return settingRepository.findByStoreIdAndSettingKey(TenantContext.requireStoreId(), key)
                .map(Setting::getValue)
                .orElse(null);
    }

    private ShippingZone findZone(String region, String commune) {
        // No region, no zone — and said up front rather than left to contains(). An immutable list
        // (List.of, which is what a zone seeded in code holds) throws NullPointerException on
        // contains(null) instead of answering false, so the same missing-region request could end
        // as a 500 or as a silent no-match depending on how the list happened to be materialised.
        if (region == null || region.isBlank()) {
            return null;
        }
        // Catálogo de comunas: se compara por nombre canónico, no por el texto exacto que alguien escribió.
        Optional<String> canonicalRegion = geoCatalog.resolveRegion(region);
        if (canonicalRegion.isEmpty()) {
            return null;
        }
        String resolvedRegion = canonicalRegion.get();
        String resolvedCommune = null;
        if (commune != null && !commune.isBlank()) {
            // Una comuna que no es de esa región no cae en ninguna zona: ni siquiera en la de región entera.
            resolvedCommune = geoCatalog.resolveCommune(resolvedRegion, commune).orElse(null);
            if (resolvedCommune == null) {
                return null;
            }
        }
        // La zona más específica gana, y el orden solo desempata entre zonas del mismo tipo. Antes ganaba la
        // primera por orden, y sin comuna servía también una zona por comunas: con una zona de solo Santiago y
        // otra de la región entera, toda la región podía pagar la tarifa de Santiago.
        ShippingZone wholeRegion = null;
        for (ShippingZone zone : zoneRepository.findByStoreIdAndIsActiveTrueOrderBySortOrderAsc(TenantContext.requireStoreId())) {
            Set<String> communes = zoneGeography.coverage(zone).get(resolvedRegion);
            if (communes == null) {
                continue;
            }
            if (communes.isEmpty()) {
                if (wholeRegion == null) {
                    wholeRegion = zone;
                }
            } else if (resolvedCommune != null && communes.contains(resolvedCommune)) {
                return zone;
            }
        }
        return wholeRegion;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ShippingOption> getBestOption(String region, String commune, BigDecimal orderAmount, BigDecimal totalWeight) {
        List<ShippingOption> options = getAvailableOptions(region, commune, orderAmount, totalWeight);
        return options.isEmpty() ? Optional.empty() : Optional.of(options.get(0));
    }

    @Override
    @Transactional(readOnly = true)
    public BigDecimal calculateTotalWeight(List<CartLineCommand> lines) {
        Long storeId = TenantContext.requireStoreId();
        BigDecimal total = BigDecimal.ZERO;
        for (CartLineCommand line : lines) {
            BigDecimal weight;
            if (line.variationId() != null) {
                ProductVariation variation = variationRepository.findById(line.variationId())
                        .filter(v -> v.getStore().getId().equals(storeId))
                        .orElseThrow(() -> new NoSuchElementException("Variation " + line.variationId() + " not found"));
                weight = variation.getWeight();
            } else {
                Product product = productRepository.findById(line.productId())
                        .filter(p -> p.getStore().getId().equals(storeId))
                        .orElseThrow(() -> new NoSuchElementException("Product " + line.productId() + " not found"));
                weight = product.getWeight();
            }
            if (weight != null) {
                total = total.add(weight.multiply(BigDecimal.valueOf(line.quantity())));
            }
        }
        return total;
    }

    boolean isApplicable(ShippingRate rate, BigDecimal orderAmount, BigDecimal totalWeight) {
        if (!rate.isActive()) return false;
        if (rate.getMinOrderAmount() != null && orderAmount.compareTo(rate.getMinOrderAmount()) < 0) return false;
        if (rate.getMaxOrderAmount() != null && orderAmount.compareTo(rate.getMaxOrderAmount()) > 0) return false;
        if (rate.getMinWeight() != null && totalWeight.compareTo(rate.getMinWeight()) < 0) return false;
        if (rate.getMaxWeight() != null && totalWeight.compareTo(rate.getMaxWeight()) > 0) return false;
        return true;
    }

    BigDecimal calculateCost(ShippingRate rate, BigDecimal orderAmount, BigDecimal totalWeight) {
        if (rate.getFreeShippingThreshold() != null && orderAmount.compareTo(rate.getFreeShippingThreshold()) >= 0) {
            return BigDecimal.ZERO;
        }
        if (rate.getRateType() == org.uvo.uvostore.entity.shipping.enums.RateType.FREE) {
            return BigDecimal.ZERO;
        }
        if (rate.getMinOrderAmount() != null && orderAmount.compareTo(rate.getMinOrderAmount()) < 0) return BigDecimal.valueOf(-1);
        if (rate.getMaxOrderAmount() != null && orderAmount.compareTo(rate.getMaxOrderAmount()) > 0) return BigDecimal.valueOf(-1);
        if (rate.getMinWeight() != null && totalWeight.compareTo(rate.getMinWeight()) < 0) return BigDecimal.valueOf(-1);
        if (rate.getMaxWeight() != null && totalWeight.compareTo(rate.getMaxWeight()) > 0) return BigDecimal.valueOf(-1);

        return switch (rate.getRateType()) {
            case FLAT -> rate.getFlatRate() != null ? rate.getFlatRate() : BigDecimal.ZERO;
            case WEIGHT_BASED -> calculateWeightBasedRate(rate, totalWeight);
            case PRICE_BASED -> rate.getFlatRate() != null ? rate.getFlatRate() : BigDecimal.ZERO;
            case FREE -> BigDecimal.ZERO;
        };
    }

    private BigDecimal calculateWeightBasedRate(ShippingRate rate, BigDecimal weight) {
        BigDecimal cost = rate.getBaseWeightRate() != null ? rate.getBaseWeightRate() : BigDecimal.ZERO;
        if (rate.getWeightRatePerKg() != null && weight.signum() > 0) {
            cost = cost.add(weight.multiply(rate.getWeightRatePerKg()));
        }
        // F06: pesos enteros. El peso sí es fraccionario (kg con decimales), pero lo que se cobra no.
        return Money.round(cost);
    }

    private String deliveryTimeString(ShippingMethod method) {
        if (method.getType() == ShippingMethodType.PICKUP) return "Inmediato";
        if (method.getMinDeliveryDays() != null && method.getMaxDeliveryDays() != null) {
            return method.getMinDeliveryDays() + "-" + method.getMaxDeliveryDays() + " días hábiles";
        }
        if (method.getMinDeliveryDays() != null) {
            return method.getMinDeliveryDays() + "+ días hábiles";
        }
        return "A coordinar";
    }
}
