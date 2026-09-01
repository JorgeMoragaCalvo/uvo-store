package org.uvo.uvostore.entity.order;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
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
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;
import org.uvo.uvostore.entity.common.Address;
import org.uvo.uvostore.entity.customer.Customer;
import org.uvo.uvostore.entity.order.enums.FulfillmentStatus;
import org.uvo.uvostore.entity.order.enums.OrderStatus;
import org.uvo.uvostore.entity.order.enums.PaymentMethodType;
import org.uvo.uvostore.entity.order.enums.PaymentStatus;
import org.uvo.uvostore.entity.shipping.ShippingMethod;
import org.uvo.uvostore.entity.shipping.ShippingRate;
import org.uvo.uvostore.entity.shipping.ShippingZone;
import org.uvo.uvostore.entity.tenant.Store;

@Entity
@Table(name = "orders")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @EqualsAndHashCode.Include
    private Long id;

    @Column(name = "order_number", nullable = false, unique = true)
    private String orderNumber;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "customer_id")
    @OnDelete(action = OnDeleteAction.SET_NULL)
    private Customer customer;

    // Guest-friendly snapshot fields — populated even when customer is null.
    @Column(name = "customer_email", nullable = false)
    private String customerEmail;

    @Column(name = "customer_first_name", nullable = false)
    private String customerFirstName;

    @Column(name = "customer_last_name", nullable = false)
    private String customerLastName;

    @Column(name = "customer_phone")
    private String customerPhone;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal subtotal;

    @Column(name = "discount_amount", nullable = false, precision = 10, scale = 2)
    @Builder.Default
    private BigDecimal discountAmount = BigDecimal.ZERO;

    @Column(name = "shipping_cost", nullable = false, precision = 10, scale = 2)
    @Builder.Default
    private BigDecimal shippingCost = BigDecimal.ZERO;

    @Column(name = "tax_amount", nullable = false, precision = 10, scale = 2)
    @Builder.Default
    private BigDecimal taxAmount = BigDecimal.ZERO;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal total;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private OrderStatus status = OrderStatus.PENDING;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_status", nullable = false)
    @Builder.Default
    private PaymentStatus paymentStatus = PaymentStatus.PENDING;

    @Enumerated(EnumType.STRING)
    @Column(name = "fulfillment_status", nullable = false)
    @Builder.Default
    private FulfillmentStatus fulfillmentStatus = FulfillmentStatus.UNFULFILLED;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_method")
    private PaymentMethodType paymentMethod;

    @Column(name = "payment_id")
    private String paymentId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payment_data", columnDefinition = "jsonb")
    private Map<String, Object> paymentData;

    @Column(name = "stripe_checkout_session_id")
    private String stripeCheckoutSessionId;

    @Column(name = "stripe_payment_intent_id")
    private String stripePaymentIntentId;

    @Column(name = "stripe_customer_id")
    private String stripeCustomerId;

    // Legacy free-text column, no relation attached in the source app —
    // superseded by shippingMethodRef. Kept for schema fidelity.
    @Column(name = "shipping_method")
    private String shippingMethod;

    // Source verification: the live column type is ambiguous (created as
    // non-nullable JSON, a later migration's attempt to redefine it as a
    // nullable string is a no-op guarded by Schema::hasColumn). Mapped as a
    // single jsonb column here — verify against information_schema before
    // writing the Flyway migration for this column.
    @Embedded
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "shipping_address", columnDefinition = "jsonb")
    private Address shippingAddress;

    @Embedded
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "billing_address", columnDefinition = "jsonb")
    private Address billingAddress;

    @Column(name = "shipping_commune")
    private String shippingCommune;

    @Column(name = "shipping_region")
    private String shippingRegion;

    @Column(name = "shipping_postal_code")
    private String shippingPostalCode;

    @Column(name = "shipping_notes", columnDefinition = "text")
    private String shippingNotes;

    @Column(name = "tracking_number")
    private String trackingNumber;

    @Column(name = "tracking_url")
    private String trackingUrl;

    @Column(name = "total_weight", precision = 10, scale = 2)
    private BigDecimal totalWeight;

    @Column(name = "shipped_at")
    private Instant shippedAt;

    @Column(name = "delivered_at")
    private Instant deliveredAt;

    @Column(name = "pos_synced", nullable = false)
    @Builder.Default
    private boolean posSynced = false;

    @Column(name = "pos_order_id")
    private String posOrderId;

    @Column(name = "sync_attempts", nullable = false)
    @Builder.Default
    private int syncAttempts = 0;

    @Column(name = "last_sync_error", columnDefinition = "text")
    private String lastSyncError;

    // C5: stock moves on payment confirmation and comes back on cancellation, and four different
    // paths can cancel an order (OrderStatusService.markCancelled/markPaymentFailed,
    // AdminOrderService.cancelOrder/updateStatus). These flags are what stops a second cancellation
    // from inventing inventory — see OrderInventoryService.
    @Column(name = "stock_applied", nullable = false)
    @Builder.Default
    private boolean stockApplied = false;

    @Column(name = "stock_restored", nullable = false)
    @Builder.Default
    private boolean stockRestored = false;

    @Column(columnDefinition = "text")
    private String notes;

    @Column(name = "customer_notes", columnDefinition = "text")
    private String customerNotes;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "store_id")
    private Store store;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "coupon_id")
    @OnDelete(action = OnDeleteAction.SET_NULL)
    private Coupon coupon;

    // Snapshot of the coupon code at the time of order, independent of the coupon FK.
    @Column(name = "coupon_code")
    private String couponCode;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "shipping_zone_id")
    @OnDelete(action = OnDeleteAction.SET_NULL)
    private ShippingZone shippingZone;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "shipping_method_id")
    @OnDelete(action = OnDeleteAction.SET_NULL)
    private ShippingMethod shippingMethodRef;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "shipping_rate_id")
    @OnDelete(action = OnDeleteAction.SET_NULL)
    private ShippingRate shippingRate;

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<OrderItem> items = new ArrayList<>();

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("createdAt DESC")
    @Builder.Default
    private List<OrderStatusHistory> statusHistory = new ArrayList<>();

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private Instant updatedAt;
}
