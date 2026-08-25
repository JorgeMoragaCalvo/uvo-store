package org.uvo.uvostore.entity.shipping;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.uvo.uvostore.entity.order.Order;
import org.uvo.uvostore.entity.payment.EncryptedCredentialsConverter;
import org.uvo.uvostore.entity.shipping.enums.ShippingCarrier;
import org.uvo.uvostore.entity.shipping.enums.ShippingMethodType;
import org.uvo.uvostore.entity.tenant.Store;

@Entity
@Table(name = "shipping_methods")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class ShippingMethod {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @EqualsAndHashCode.Include
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String code;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "store_id")
    private Store store;

    @Column(columnDefinition = "text")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private ShippingMethodType type = ShippingMethodType.COURIER;

    @Column(name = "has_api_integration", nullable = false)
    @Builder.Default
    private boolean hasApiIntegration = false;

    // Which quote-only carrier integration this method talks to when hasApiIntegration=true — see
    // service.shipping.carrier.ShippingCarrierQuoteDispatcher. Null for purely local/static
    // methods (flat/weight/price-based rates from ShippingRate).
    @Enumerated(EnumType.STRING)
    @Column
    private ShippingCarrier carrier;

    // Encrypted at rest — same converter/shape as PaymentGatewayConfig.credentials. Shape depends
    // on `carrier`: CHILEXPRESS: subscriptionKey, originCountyCode. CORREOS_CHILE: unset (stub,
    // no real integration yet — see CorreosChileQuoteClient).
    @Convert(converter = EncryptedCredentialsConverter.class)
    @Column(name = "api_credentials", columnDefinition = "text")
    @Builder.Default
    private Map<String, String> apiCredentials = new HashMap<>();

    @Column(name = "min_delivery_days")
    private Integer minDeliveryDays;

    @Column(name = "max_delivery_days")
    private Integer maxDeliveryDays;

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private boolean isActive = true;

    @Column(name = "sort_order", nullable = false)
    @Builder.Default
    private int sortOrder = 0;

    @OneToMany(mappedBy = "method")
    @Builder.Default
    private List<ShippingRate> rates = new ArrayList<>();

    @OneToMany(mappedBy = "shippingMethodRef")
    @Builder.Default
    private List<Order> orders = new ArrayList<>();

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private Instant updatedAt;
}
