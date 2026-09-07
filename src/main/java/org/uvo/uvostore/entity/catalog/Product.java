package org.uvo.uvostore.entity.catalog;

import org.hibernate.annotations.BatchSize;
import jakarta.persistence.CascadeType;
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
import jakarta.persistence.OneToMany;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import lombok.AccessLevel;
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
import org.uvo.uvostore.entity.catalog.enums.ProductType;
import org.uvo.uvostore.entity.order.OrderItem;
import org.uvo.uvostore.entity.pos.ProductSyncMapping;
import org.uvo.uvostore.entity.tenant.Store;

@Entity
@Table(name = "products")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class Product {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @EqualsAndHashCode.Include
    private Long id;

    @Column
    private String sku;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String slug;

    @Column(name = "short_description", columnDefinition = "text")
    private String shortDescription;

    @Column(columnDefinition = "text")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "product_type", nullable = false)
    @Builder.Default
    private ProductType productType = ProductType.SIMPLE;

    @Column(precision = 10, scale = 2)
    private BigDecimal price;

    @Column(name = "sale_price", precision = 10, scale = 2)
    private BigDecimal salePrice;

    @Column(nullable = false)
    @Builder.Default
    private int stock = 0;

    @Column(precision = 10, scale = 2)
    private BigDecimal weight;

    @Column(name = "manage_stock", nullable = false)
    @Builder.Default
    private boolean manageStock = true;

    @Column(nullable = false)
    @Builder.Default
    private boolean active = true;

    // Legacy boolean, superseded by isFeatured — no application code reads this
    // anymore (source verification: HomeProductScopes::scopeFeatured() filters on
    // is_featured, not featured). Kept for schema fidelity.
    @Column(nullable = false)
    @Builder.Default
    @Getter(AccessLevel.NONE)
    @Setter(AccessLevel.NONE)
    private boolean featured = false;

    @Column(name = "is_featured", nullable = false)
    @Builder.Default
    private boolean isFeatured = false;

    @Column(name = "is_new", nullable = false)
    @Builder.Default
    private boolean isNew = false;

    @Column(name = "is_on_sale", nullable = false)
    @Builder.Default
    private boolean isOnSale = false;

    @Column(name = "new_until")
    private LocalDate newUntil;

    @Column(name = "sale_starts_at")
    private Instant saleStartsAt;

    @Column(name = "sale_ends_at")
    private Instant saleEndsAt;

    @Column(name = "discount_percentage")
    private Integer discountPercentage;

    @Column(name = "sort_order", nullable = false)
    @Builder.Default
    private int sortOrder = 0;

    @Column(name = "views_count", nullable = false)
    @Builder.Default
    private int viewsCount = 0;

    @Column(name = "sales_count", nullable = false)
    @Builder.Default
    private int salesCount = 0;

    @Column(name = "meta_title")
    private String metaTitle;

    @Column(name = "meta_description", columnDefinition = "text")
    private String metaDescription;

    // Legacy JSON column, excluded from the Laravel model's fillable/casts —
    // superseded by the ProductImage entity/table. Kept for schema fidelity only.
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private List<String> images;

    // Legacy, excluded from Laravel model's fillable — superseded by ProductImage.
    @Column(name = "featured_image")
    private String featuredImage;

    @Column(name = "pos_product_id")
    private String posProductId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "category_id")
    @OnDelete(action = OnDeleteAction.SET_NULL)
    private Category category;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "store_id")
    private Store store;

    // M7: the admin listing loaded 20 products and then fired one SELECT per product for its
    // images, one more for its variations, and one per variation for the attribute assignments —
    // 1 + N + N + N×V queries for a single page. @BatchSize makes Hibernate collect the pending
    // proxies and fetch them in IN (...) batches, so a page costs a constant handful of queries no
    // matter how many rows it has.
    //
    // Not JOIN FETCH: the listing is findAll(spec, pageable), and a fetch join across a collection
    // with pagination forces Hibernate to page in memory (HHH90003004) — it would read every
    // product in the store to hand back 20. @BatchSize leaves the query and the paging untouched.
    @OneToMany(mappedBy = "product", cascade = CascadeType.ALL, orphanRemoval = true)
    @BatchSize(size = 50)
    @Builder.Default
    private List<ProductImage> productImages = new ArrayList<>();

    @OneToMany(mappedBy = "product", cascade = CascadeType.ALL, orphanRemoval = true)
    @BatchSize(size = 50)
    @Builder.Default
    private List<ProductVariation> variations = new ArrayList<>();

    @OneToMany(mappedBy = "product")
    @Builder.Default
    private List<OrderItem> orderItems = new ArrayList<>();

    // M7: aquí vivía `@OneToOne(mappedBy = "product") private ProductSyncMapping syncMapping;`, y
    // costaba una consulta por producto en cada listado. Un uno-a-uno inverso no se puede
    // representar con un proxy —Hibernate no sabe si hay fila al otro lado sin ir a mirar—, así que
    // se carga siempre, ignorando FetchType.LAZY y sin que @BatchSize lo alcance. Con la paginación
    // del panel eso era el 1+N que quedaba después de los @BatchSize de las colecciones.
    //
    // El campo no lo leía nadie (cero usos en src/main y src/test). La relación sigue existiendo por
    // su lado dueño, ProductSyncMapping.product, que es por donde ProductSyncService la usa de
    // verdad; quien necesite el mapeo de un producto lo pide por ProductSyncMappingRepository.

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private Instant updatedAt;
}
