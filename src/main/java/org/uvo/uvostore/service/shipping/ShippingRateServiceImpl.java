package org.uvo.uvostore.service.shipping;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.uvo.uvostore.entity.catalog.Product;
import org.uvo.uvostore.entity.catalog.ProductVariation;
import org.uvo.uvostore.entity.shipping.ShippingMethod;
import org.uvo.uvostore.entity.shipping.ShippingRate;
import org.uvo.uvostore.entity.shipping.ShippingZone;
import org.uvo.uvostore.entity.shipping.enums.ShippingMethodType;
import org.uvo.uvostore.repository.ProductRepository;
import org.uvo.uvostore.repository.ProductVariationRepository;
import org.uvo.uvostore.repository.ShippingRateRepository;
import org.uvo.uvostore.repository.ShippingZoneRepository;
import org.uvo.uvostore.security.TenantContext;
import org.uvo.uvostore.service.order.CartLineCommand;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

@Service
public class ShippingRateServiceImpl implements ShippingRateService {

    private final ShippingZoneRepository zoneRepository;
    private final ShippingRateRepository rateRepository;
    private final ProductRepository productRepository;
    private final ProductVariationRepository variationRepository;

    public ShippingRateServiceImpl(ShippingZoneRepository zoneRepository, ShippingRateRepository rateRepository, ProductRepository productRepository, ProductVariationRepository variationRepository) {
        this.zoneRepository = zoneRepository;
        this.rateRepository = rateRepository;
        this.productRepository = productRepository;
        this.variationRepository = variationRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public List<ShippingOption> getAvailableOptions(String region, String commune, BigDecimal orderAmount, BigDecimal totalWeight) {
        ShippingZone zone = findZone(region, commune);
        if (zone == null) {
            return List.of();
        }

        List<ShippingOption> options = new ArrayList<>();
        for (ShippingRate rate : rateRepository.findByZoneIdAndIsActiveTrue(zone.getId())) {
            if (!isApplicable(rate, orderAmount, totalWeight)) continue;
            BigDecimal cost = calculateCost(rate, orderAmount, totalWeight);
            if (cost.signum() < 0) continue;
            options.add(new ShippingOption(
                    rate.getMethod().getId(),
                    rate.getMethod().getName(),
                    cost,
                    deliveryTimeString(rate.getMethod()),
                    cost.signum() == 0
            ));
        }

        options.sort(Comparator.comparing(ShippingOption::cost));
        return options;
    }

    private ShippingZone findZone(String region, String commune) {
        for (ShippingZone zone : zoneRepository.findByStoreIdAndIsActiveTrueOrderBySortOrderAsc(TenantContext.requireStoreId())) {
            if (zone.getRegions() != null && zone.getRegions().contains(region)){
                if (zone.getCommunes() != null && commune != null) {
                    if (zone.getCommunes().contains(commune)) return zone;
                } else {
                    return zone;
                }
            }
        }
        return null;
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
        return cost.setScale(2, RoundingMode.HALF_UP);
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
