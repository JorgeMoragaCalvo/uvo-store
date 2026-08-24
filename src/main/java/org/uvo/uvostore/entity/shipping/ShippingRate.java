package org.uvo.uvostore.entity.shipping;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import org.hibernate.annotations.UpdateTimestamp;
import org.uvo.uvostore.entity.shipping.enums.RateType;
import org.uvo.uvostore.entity.tenant.Store;

@Entity
@Table(name = "shipping_rates")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class ShippingRate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @EqualsAndHashCode.Include
    private Long id;

    @Column(nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "rate_type", nullable = false)
    @Builder.Default
    private RateType rateType = RateType.FLAT;

    @Column(name = "flat_rate", precision = 10, scale = 2)
    private BigDecimal flatRate;

    @Column(name = "weight_rate_per_kg", precision = 10, scale = 2)
    private BigDecimal weightRatePerKg;

    @Column(name = "base_weight_rate", precision = 10, scale = 2)
    private BigDecimal baseWeightRate;

    @Column(name = "min_order_amount", precision = 10, scale = 2)
    private BigDecimal minOrderAmount;

    @Column(name = "max_order_amount", precision = 10, scale = 2)
    private BigDecimal maxOrderAmount;

    @Column(name = "min_weight", precision = 10, scale = 2)
    private BigDecimal minWeight;

    @Column(name = "max_weight", precision = 10, scale = 2)
    private BigDecimal maxWeight;

    @Column(name = "free_shipping_threshold", precision = 10, scale = 2)
    private BigDecimal freeShippingThreshold;

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private boolean isActive = true;

    @Column(name = "sort_order", nullable = false)
    @Builder.Default
    private int sortOrder = 0;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "shipping_method_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private ShippingMethod method;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "shipping_zone_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private ShippingZone zone;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "store_id")
    private Store store;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private Instant updatedAt;
}
