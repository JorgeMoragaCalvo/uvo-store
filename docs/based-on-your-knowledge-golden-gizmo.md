# UvoStore → Spring Boot: JPA Entity Migration Plan

## Context

UvoStore is currently a Laravel 12 / Eloquent app (SQLite, 34 migrations) with three surfaces (admin Livewire panel, Vue 3 SPA, REST API for an external POS system). The user wants to evaluate/plan a migration to Spring Boot, and has asked — as a scoped first step — for **only the entity layer**: JPA `@Entity` classes, their fields, relationships, keys, and enums, derived accurately from the real schema (`database/migrations/*.php`) and real Eloquent models (`app/Models/*.php`), not guessed. Framework-internal Laravel tables (`sessions`, `cache`, `jobs`, `failed_jobs`, `password_reset_tokens`, `telescope_*`) are excluded — Spring Boot has its own equivalents (Spring Session, Spring Cache, Spring Batch/Quartz) and shouldn't be ported 1:1.

Two Explore agents read every migration and every model; this plan reconciles both (migrations are authoritative for columns/constraints, models are authoritative for relationship semantics/derived behavior).

## Package layout

```
com.uvostore.domain.catalog     Product, ProductVariation, ProductImage, Category, Attribute, AttributeValue, ProductVariationAttribute
com.uvostore.domain.order       Order, OrderItem, OrderStatusHistory, Coupon, CouponUsage
com.uvostore.domain.customer    Customer, ShippingAddress
com.uvostore.domain.shipping    ShippingZone, ShippingMethod, ShippingRate
com.uvostore.domain.pos         PosConnection, ProductSyncMapping, SyncWebhookLog
com.uvostore.domain.settings    Setting, StoreSettings, HomeBanner
com.uvostore.domain.security    User, Role, Permission (+ join entities or @ManyToMany)
com.uvostore.domain.audit       ActivityLog (optional — or replace with Hibernate Envers)
```

All entities: `Long id` with `@Id @GeneratedValue(strategy = GenerationType.IDENTITY)`, `Instant createdAt` / `updatedAt` via `@CreationTimestamp`/`@UpdateTimestamp` (mirrors Eloquent timestamps), money fields as `BigDecimal` with `precision=10, scale=2`, JSON columns as `@JdbcTypeCode(SqlTypes.JSON)` (Hibernate 6) mapped to `Map<String,Object>`/`List<...>`/a small `@Embeddable`, per-field.

---

## Catalog domain

### `Category`
- `id`, `name`, `slug` (unique), `description`, `image`, `icon`
- `active` (bool, default true), `isFeatured` (bool), `sortOrder` (int), `productsCount` (int)
- `parent`: `@ManyToOne @JoinColumn(name="parent_id")` → `Category`, `onDelete=CASCADE` (self-referential)
- `children`: `@OneToMany(mappedBy="parent")`, ordered by `sortOrder`
- `products`: `@OneToMany(mappedBy="category")`

### `Attribute`
- `id`, `name`, `slug` (unique)
- `type`: enum `AttributeType { SELECT, SWATCH, BUTTON }`
- `values`: `@OneToMany(mappedBy="attribute", cascade=ALL, orphanRemoval=true)` ordered by `sortOrder`

### `AttributeValue`
- `id`, `value`, `slug`, `colorHex` (nullable), `sortOrder`
- `attribute`: `@ManyToOne @JoinColumn(name="attribute_id")`, `onDelete=CASCADE`
- Unique constraint: `(attribute_id, slug)`

### `Product`
- `id`, `sku` (unique, nullable), `name`, `slug` (unique), `shortDescription`, `description`
- `productType`: enum `ProductType { SIMPLE, VARIABLE }`
- `price`, `salePrice` (BigDecimal, nullable), `stock` (int), `weight` (BigDecimal, nullable)
- `manageStock`, `active`, `featured`, `isFeatured`, `isNew`, `isOnSale` (bool)
- `newUntil` (LocalDate, nullable), `saleStartsAt`/`saleEndsAt` (Instant, nullable)
- `discountPercentage` (int, nullable), `sortOrder`, `viewsCount`, `salesCount` (int)
- `metaTitle`, `metaDescription`
- `images` (json, nullable — legacy denormalized array; consider dropping in favor of `ProductImage` entity), `featuredImage` (string, nullable)
- `posProductId` (string, nullable, indexed)
- `category`: `@ManyToOne @JoinColumn(name="category_id")`, `onDelete=SET NULL` → nullable FK
- `images` (entity): `@OneToMany(mappedBy="product", cascade=ALL, orphanRemoval=true)`
- `variations`: `@OneToMany(mappedBy="product", cascade=ALL, orphanRemoval=true)`
- `orderItems`: `@OneToMany(mappedBy="product")`
- `syncMapping`: `@OneToOne(mappedBy="product")` → `ProductSyncMapping`
- Note: `is_featured` vs `featured`, and `images` (json) vs `ProductImage` entity are duplicate/legacy columns in the current schema — flag for the user to decide whether to collapse them during migration rather than porting both.

### `ProductVariation`
- `id`, `sku` (unique), `price`, `compareAtPrice` (nullable), `stock` (int), `weight` (nullable)
- `dimensions` (json, nullable — map to `@Embeddable Dimensions{length,width,height}` if fields are consistent, else `Map<String,Object>`)
- `image` (string, nullable), `active` (bool), `posProductId` (nullable)
- `product`: `@ManyToOne @JoinColumn(name="product_id")`, `onDelete=CASCADE`
- `attributeValues`: `@ManyToMany` through join entity `ProductVariationAttribute` (see below) — **not** a plain `@ManyToMany` because the pivot carries an extra `attribute_id` column
- `orderItems`: `@OneToMany(mappedBy="variation")`

### `ProductVariationAttribute` (explicit join entity, not `@ManyToMany`)
- `id`
- `variation`: `@ManyToOne @JoinColumn(name="product_variation_id")`, `onDelete=CASCADE`
- `attribute`: `@ManyToOne @JoinColumn(name="attribute_id")`, `onDelete=CASCADE`
- `attributeValue`: `@ManyToOne @JoinColumn(name="attribute_value_id")`, `onDelete=CASCADE`
- Unique constraint: `(product_variation_id, attribute_id)`
- Reasoning: the Laravel pivot table `product_variation_attributes` stores `attribute_id` *and* `attribute_value_id` together (redundant but present), so a real entity with three `@ManyToOne`s reproduces it correctly; a bare `@ManyToMany` on `AttributeValue` would lose the `attribute_id` column.

### `ProductImage`
- `id`, `imagePath`, `altText` (nullable), `sortOrder`, `isFeatured` (bool)
- `type`: enum `ImageType { GALLERY, THUMBNAIL, HERO }`
- `product`: `@ManyToOne @JoinColumn(name="product_id")`, `onDelete=CASCADE`

---

## Order domain

### `Order`
- `id`, `orderNumber` (unique, generated `ORD-yyyyMMdd-NNNNN`)
- Guest-friendly snapshot fields: `customerEmail`, `customerFirstName`, `customerLastName`, `customerPhone`
- `subtotal`, `discountAmount`, `shippingCost`, `taxAmount`, `total` (BigDecimal)
- `status`: enum `OrderStatus { PENDING, PAID, PROCESSING, SHIPPED, DELIVERED, CANCELLED, REFUNDED }`
- `paymentStatus`: enum `PaymentStatus { PENDING, PAID, FAILED, REFUNDED }`
- `fulfillmentStatus`: enum `FulfillmentStatus { UNFULFILLED, PARTIAL, FULFILLED }`
- `paymentMethod`, `paymentId` (nullable), `paymentData` (json, nullable)
- `stripeCheckoutSessionId`, `stripePaymentIntentId`, `stripeCustomerId` (nullable, indexed)
- `shippingMethod` (string, legacy), `shippingAddress`/`billingAddress` (json — map to `@Embeddable Address`)
- `shippingCommune`, `shippingRegion`, `shippingPostalCode`, `shippingNotes`, `trackingNumber`, `trackingUrl` (nullable)
- `totalWeight` (BigDecimal, nullable)
- `shippedAt`, `deliveredAt` (Instant, nullable)
- `posSynced` (bool), `posOrderId` (nullable), `syncAttempts` (int), `lastSyncError` (text, nullable)
- `notes`, `customerNotes` (nullable)
- `customer`: `@ManyToOne @JoinColumn(name="customer_id")`, `onDelete=SET NULL`, nullable (guest orders)
- `coupon`: `@ManyToOne @JoinColumn(name="coupon_id")`, `onDelete=SET NULL`, nullable + snapshot `couponCode` string
- `shippingZone`: `@ManyToOne`, `onDelete=SET NULL`, nullable
- `shippingMethodRef`: `@ManyToOne @JoinColumn(name="shipping_method_id")`, `onDelete=SET NULL`, nullable (distinct from the legacy `shippingMethod` string column — flag as duplication to resolve)
- `shippingRate`: `@ManyToOne`, `onDelete=SET NULL`, nullable
- `items`: `@OneToMany(mappedBy="order", cascade=ALL, orphanRemoval=true)`
- `statusHistory`: `@OneToMany(mappedBy="order", cascade=ALL, orphanRemoval=true)`, ordered by `createdAt DESC`

### `OrderItem`
- `id`, `productName`, `productSku` (snapshot at time of order), `posProductId` (nullable)
- `variationDetails` (json, nullable → `Map<String,String>`)
- `quantity` (int), `price`, `subtotal`, `taxAmount` (BigDecimal)
- `order`: `@ManyToOne @JoinColumn(name="order_id")`, `onDelete=CASCADE`
- `product`: `@ManyToOne @JoinColumn(name="product_id")`, `onDelete=RESTRICT`
- `variation`: `@ManyToOne @JoinColumn(name="product_variation_id")`, `onDelete=RESTRICT`, nullable

### `OrderStatusHistory`
- `id`, `status` (string in DB — recommend enum on the Java side matching a superset of order/payment statuses), `notes` (nullable)
- `order`: `@ManyToOne @JoinColumn(name="order_id")`, `onDelete=CASCADE`
- `user`: `@ManyToOne @JoinColumn(name="user_id")`, nullable, default FK behavior (no cascade)

### `Coupon`
- `id`, `code` (unique), `name`, `description` (nullable)
- `type`: enum `CouponType { PERCENTAGE, FIXED }`
- `value`, `minimumPurchase`, `maximumDiscount` (BigDecimal, nullable)
- `startsAt`, `expiresAt` (Instant, nullable)
- `usageLimit`, `usageLimitPerCustomer` (int, nullable), `timesUsed` (int)
- `isActive` (bool)
- `orders`: `@OneToMany(mappedBy="coupon")`
- `usages`: `@OneToMany(mappedBy="coupon", cascade=ALL)`

### `CouponUsage`
- `id`, `discountAmount` (BigDecimal)
- `coupon`: `@ManyToOne`, `onDelete=CASCADE`
- `order`: `@ManyToOne`, `onDelete=CASCADE`
- `customer`: `@ManyToOne`, `onDelete=SET NULL`, nullable

---

## Customer domain

### `Customer`
- `id`, `email` (unique), `password` (nullable — guest checkout), `firstName`, `lastName`, `phone` (nullable)
- `accountStatus`: enum `AccountStatus { GUEST, INVITED, ACTIVE }`
- `invitationToken` (nullable), `invitationSentAt` (Instant, nullable)
- Implements a Spring Security `UserDetails`-adapting principal (separate guard from admin `User`, matching Laravel's two-auth-system design — do not merge into one table)
- `orders`: `@OneToMany(mappedBy="customer")`
- `shippingAddresses`: `@OneToMany(mappedBy="customer", cascade=ALL, orphanRemoval=true)`

### `ShippingAddress`
- `id`, `firstName`, `lastName`, `company` (nullable), `addressLine1`, `addressLine2` (nullable)
- `city`, `state`, `postalCode`, `country` (default `"CL"`), `phone`
- `isDefault` (bool) — enforce "only one default per customer" with an `@PrePersist/@PreUpdate` listener or DB partial unique index, mirroring the Eloquent `saving` event
- `customer`: `@ManyToOne @JoinColumn(name="customer_id")`, `onDelete=CASCADE`

---

## Shipping domain

### `ShippingZone`
- `id`, `name`, `description` (nullable), `regions` (json → `List<String>`), `communes` (json → `List<String>`), `isActive`, `sortOrder`
- `rates`: `@OneToMany(mappedBy="zone")`
- `orders`: `@OneToMany(mappedBy="shippingZone")`

### `ShippingMethod`
- `id`, `name`, `code` (unique), `description` (nullable)
- `type`: enum `ShippingMethodType { COURIER, PICKUP, CUSTOM }`
- `hasApiIntegration` (bool), `apiCredentials` (json, nullable → treat as encrypted/`@Convert` if secrets)
- `minDeliveryDays`, `maxDeliveryDays` (int, nullable), `isActive`, `sortOrder`
- `rates`: `@OneToMany(mappedBy="method")`
- `orders`: `@OneToMany(mappedBy="shippingMethodRef")`

### `ShippingRate`
- `id`, `name`
- `rateType`: enum `RateType { FLAT, WEIGHT_BASED, PRICE_BASED, FREE }`
- `flatRate`, `weightRatePerKg`, `baseWeightRate`, `minOrderAmount`, `maxOrderAmount`, `minWeight`, `maxWeight`, `freeShippingThreshold` (BigDecimal, all nullable)
- `isActive`, `sortOrder`
- `method`: `@ManyToOne @JoinColumn(name="shipping_method_id")`, `onDelete=CASCADE`
- `zone`: `@ManyToOne @JoinColumn(name="shipping_zone_id")`, `onDelete=CASCADE`

---

## POS sync domain

### `PosConnection`
- `id`, `companyName`, `companyId` (unique, unsigned bigint), `apiUrl`, `apiKey` (secret — store encrypted), `webhookSecret` (secret)
- `isActive` (bool), `connectedAt`, `lastPingAt` (Instant, nullable)
- `productMappings`: `@OneToMany` joined **not** by `id` but by `companyId` — i.e. `@JoinColumn(name="company_id", referencedColumnName="company_id")`, matching the non-standard key used in Eloquent. Confirm this is intentional (multiple `PosConnection` rows could share ambiguity) rather than a modeling bug before porting literally.

### `ProductSyncMapping`
- `id`, `externalId` (unsigned bigint), `externalSku`, `companyId`, `warehouseId` (nullable)
- `syncStatus`: enum `SyncStatus { ACTIVE, PAUSED, ERROR }`
- `syncDirection`: enum `SyncDirection { FROM_POS, TO_POS, BIDIRECTIONAL }`
- `syncError` (nullable), `lastSyncedAt` (Instant, nullable)
- `syncStock`, `syncPrice`, `syncName`, `syncDescription` (bool flags)
- `syncMetadata` (json, nullable)
- `product`: `@OneToOne @JoinColumn(name="product_id")`, `onDelete=CASCADE`
- `posConnection`: `@ManyToOne @JoinColumn(name="company_id", referencedColumnName="company_id")`
- Unique constraints: `(external_id, company_id)`, `(product_id, company_id)`

### `SyncWebhookLog`
- `id`, `companyId`, `eventType`
- `status`: enum `WebhookStatus { RECEIVED, PROCESSING, SUCCESS, FAILED }`
- `payload` (json, nullable), `errorMessage` (nullable), `processedAt` (Instant, nullable)
- No relationships (standalone log/audit table)

---

## Settings / CMS domain

### `Setting`
- `id`, `key` (unique), `value` (text, nullable) — generic key/value store; consider replacing with Spring's `@ConfigurationProperties` + DB-backed override table only if runtime-editable config is truly needed

### `StoreSettings`
- Singleton row (`current()` pattern in Laravel) — model as a single-row entity or migrate to a proper config service. All branding/theme/home-section-toggle/contact/SEO/benefit fields as plain columns (~35 fields, no relationships). No FKs.

### `HomeBanner`
- `id`, `title`, `subtitle`/`description` (nullable), `image`, `mobileImage` (nullable)
- `ctaText`, `ctaLink`, `ctaNewTab` (bool), `ctaSecondaryText`/`ctaSecondaryLink` (nullable)
- `textPosition`: enum `TextPosition { LEFT, CENTER, RIGHT }`
- `textColor`: enum `TextColor { LIGHT, DARK }`
- `overlayColor` (nullable), `overlayOpacity` (int)
- `active` (bool), `sortOrder` (int)
- No relationships

---

## Security domain (replaces Spatie permission package)

Laravel's admin `User` uses Spatie `HasRoles`, giving polymorphic `model_has_roles`/`model_has_permissions`/`role_has_permissions`. Spring Security doesn't need the polymorphism (no separate "roles for Customer" requirement observed — `Customer` has its own guard with no roles/permissions at all), so simplify to a direct model rather than porting the polymorphic pivot literally:

### `User` (admin)
- `id`, `name`, `email` (unique), `password`, `phone` (nullable), `avatar` (nullable)
- `isActive`, `isAdmin` (bool), `lastLoginAt` (Instant, nullable), `notes` (text, nullable)
- `invitationToken` (unique, nullable), `invitationSentAt`, `invitationAcceptedAt` (nullable)
- `stripeCustomerId` (nullable, indexed) — flag: this looks vestigial/unused for an admin user; confirm before porting
- `roles`: `@ManyToMany @JoinTable(name="user_roles")` → `Role`
- `statusHistoryEntries`: `@OneToMany(mappedBy="user")` → `OrderStatusHistory`

### `Role`
- `id`, `name` (unique)
- `permissions`: `@ManyToMany @JoinTable(name="role_permissions")` → `Permission`

### `Permission`
- `id`, `name` (unique) — e.g. `"users.view"`, matching existing `.can('users.view')` checks in the admin routes; keep the same permission-name strings so route guards translate directly to `@PreAuthorize("hasAuthority('users.view')")`

(This collapses `model_has_permissions`/`model_has_roles`/`role_has_permissions` into two simple `@ManyToMany` join tables since only `User` — not `Customer` — carries roles/permissions today.)

### `ActivityLog` (optional)
- `id`, `logName` (nullable), `event` (nullable), `description`
- `subjectType`, `subjectId` (polymorphic — Spring has no native polymorphic association; either use a `@DiscriminatorColumn`-based approach, string+id pair resolved manually, or **prefer Hibernate Envers** for audit trails instead of porting this table verbatim)
- `causerType`, `causerId` (same caveat)
- `properties` (json, nullable), `batchUuid` (UUID, nullable)

---

## Excluded from entity migration (flagged, not ported)

- `sessions`, `cache`, `cache_locks`, `jobs`, `job_batches`, `failed_jobs`, `password_reset_tokens` — Laravel framework plumbing; use Spring Session, Spring Cache/Redis, Spring Batch or a message queue, and Spring Security's password-reset-token flow instead.
- `telescope_entries`, `telescope_entries_tags`, `telescope_monitoring` — Laravel Telescope debug tool; use Spring Boot Actuator/Micrometer instead.

## Data issues to resolve during migration (not entity-blocking, but will produce ugly entities if ignored)

1. `products.is_featured` vs `products.featured` — two boolean columns doing the same job.
2. `products.images` (json) vs the `ProductImage` entity table — pick one source of truth.
3. `orders.shipping_method` (legacy string) vs `orders.shipping_method_id` (FK to `ShippingMethod`) — same duplication pattern.
4. `PosConnection` ↔ `ProductSyncMapping` join on `company_id` rather than `id` — non-standard, verify intent with whoever owns the POS integration before encoding it as canon in JPA.

## Verification

Since this is an entity-only design deliverable (no runtime code yet), "verification" means a paper review, not test execution:
- Cross-check each entity's fields against `database/migrations/*.php` column list once implemented.
- Cross-check each relationship against `app/Models/*.php` relationship methods.
- When entities are actually implemented in a later phase, generate DDL via `spring.jpa.hibernate.ddl-auto=validate` against a schema created from Flyway/Liquibase migrations translated from the Laravel migrations, and confirm no mapping exceptions on `ApplicationContext` startup.
