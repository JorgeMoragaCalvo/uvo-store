package org.uvo.uvostore.entity.pos;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.uvo.uvostore.entity.tenant.Store;

@Entity
@Table(name = "pos_connections")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class PosConnection {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @EqualsAndHashCode.Include
    private Long id;

    @Column(name = "company_name", nullable = false)
    private String companyName;

    // Unique but NOT the primary/foreign key used to link to ProductSyncMapping
    // in the app — see productMappings below and ProductSyncMapping.posConnection.
    @Column(name = "company_id", nullable = false, unique = true)
    private Long companyId;

    @Column(name = "api_url", nullable = false)
    private String apiUrl;

    // Secret — store encrypted (e.g. via a JPA AttributeConverter), never log.
    @Column(name = "api_key", nullable = false)
    private String apiKey;

    // Secret — store encrypted.
    @Column(name = "webhook_secret", nullable = false)
    private String webhookSecret;

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private boolean isActive = true;

    @Column(name = "connected_at")
    private Instant connectedAt;

    @Column(name = "last_ping_at")
    private Instant lastPingAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "store_id")
    private Store store;

    // Source verification: joined on company_id, not id — product_sync_mapping.company_id
    // carries no DB-level FK constraint (plain unsignedBigInteger in the source schema),
    // so this is an application-level equality join only; do not rely on referential
    // integrity or cascading deletes here.
    @OneToMany(mappedBy = "posConnection")
    @Builder.Default
    private List<ProductSyncMapping> productMappings = new ArrayList<>();

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private Instant updatedAt;
}
