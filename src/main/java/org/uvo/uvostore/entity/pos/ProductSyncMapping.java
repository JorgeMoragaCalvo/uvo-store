package org.uvo.uvostore.entity.pos;

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
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
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
import org.uvo.uvostore.entity.catalog.Product;
import org.uvo.uvostore.entity.pos.enums.SyncDirection;
import org.uvo.uvostore.entity.pos.enums.SyncStatus;

/**
 * Source verification: the real Laravel table is named singular
 * "product_sync_mapping" (the model explicitly overrides $table), not the
 * pluralized default — mapped here to match.
 */
@Entity
@Table(
        name = "product_sync_mapping",
        uniqueConstraints = {
            @UniqueConstraint(
                    name = "unique_external_product",
                    columnNames = {"external_id", "company_id"}),
            @UniqueConstraint(
                    name = "unique_store_product",
                    columnNames = {"product_id", "company_id"})
        })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class ProductSyncMapping {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @EqualsAndHashCode.Include
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Product product;

    // UvoPOS's producto_empresa_id — plain bigint, not a JPA-managed FK.
    @Column(name = "external_id", nullable = false)
    private Long externalId;

    @Column(name = "external_sku", nullable = false, length = 100)
    private String externalSku;

    // UvoPOS's empresa_id. Source verification: no DB-level FK constraint exists
    // on this column — the join to PosConnection is an application-level equality
    // match on company_id, not a real foreign key. See posConnection below.
    @Column(name = "company_id", nullable = false)
    private Long companyId;

    @Column(name = "warehouse_id")
    private Long warehouseId;

    @Enumerated(EnumType.STRING)
    @Column(name = "sync_status", nullable = false)
    @Builder.Default
    private SyncStatus syncStatus = SyncStatus.ACTIVE;

    @Enumerated(EnumType.STRING)
    @Column(name = "sync_direction", nullable = false)
    @Builder.Default
    private SyncDirection syncDirection = SyncDirection.FROM_POS;

    @Column(name = "sync_error", columnDefinition = "text")
    private String syncError;

    @Column(name = "last_synced_at")
    private Instant lastSyncedAt;

    @Column(name = "sync_stock", nullable = false)
    @Builder.Default
    private boolean syncStock = true;

    @Column(name = "sync_price", nullable = false)
    @Builder.Default
    private boolean syncPrice = true;

    @Column(name = "sync_name", nullable = false)
    @Builder.Default
    private boolean syncName = false;

    @Column(name = "sync_description", nullable = false)
    @Builder.Default
    private boolean syncDescription = false;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "sync_metadata", columnDefinition = "jsonb")
    private Map<String, Object> syncMetadata;

    // Non-standard: joined on PosConnection.companyId, not PosConnection.id.
    // No DB-level FK constraint backs this relationship (see companyId above) —
    // do not apply @OnDelete cascade behavior here, there's no referential
    // integrity to mirror.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "company_id",
            referencedColumnName = "company_id",
            insertable = false,
            updatable = false)
    private PosConnection posConnection;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private Instant updatedAt;
}
