package org.uvo.uvostore.entity.payment;

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
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.uvo.uvostore.entity.payment.enums.PaymentGatewayType;
import org.uvo.uvostore.entity.tenant.Store;

// One row per (store, gateway) — a store can have several gateways configured, at most one
// enabled at a time is a checkout-time business rule, not something enforced here at the schema
// level (Order.paymentMethod is validated against whichever gateway is actually enabled).
@Entity
@Table(name = "payment_gateway_configs",
        uniqueConstraints = @UniqueConstraint(columnNames = {"store_id", "gateway"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class PaymentGatewayConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @EqualsAndHashCode.Include
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "store_id", nullable = false)
    private Store store;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PaymentGatewayType gateway;

    @Column(nullable = false)
    @Builder.Default
    private boolean enabled = false;

    // Encrypted at rest — see EncryptedCredentialsConverter. Shape depends on `gateway`:
    // STRIPE: secretKey, webhookSecret, publicKey
    // WEBPAY: commerceCode, apiKey, environment ("integration"|"production")
    // MERCADOPAGO: accessToken, publicKey
    @Convert(converter = EncryptedCredentialsConverter.class)
    @Column(columnDefinition = "text")
    @Builder.Default
    private Map<String, String> credentials = new HashMap<>();

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private Instant updatedAt;
}
