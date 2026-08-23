package org.uvo.uvostore.entity.settings;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.uvo.uvostore.entity.tenant.Store;

/**
 * Was a singleton row (Laravel's current() = Cache::remember(..., firstOrCreate([]))).
 * Since the Fase 0 multi-tenant retrofit it's one row per store, DB-enforced via
 * UNIQUE(store_id) — the service layer still needs updating to fetch/create by the
 * current tenant instead of "row id=1" (tracked as a later sub-step, not done here).
 */
@Entity
@Table(name = "store_settings")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class StoreSettings {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @EqualsAndHashCode.Include
    private Long id;

    @Column(name = "store_name", nullable = false)
    @Builder.Default
    private String storeName = "UvoStore";

    @Column(name = "store_logo")
    private String storeLogo;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "store_id")
    private Store store;

    @Column(name = "store_favicon")
    private String storeFavicon;

    @Column(name = "store_description", columnDefinition = "text")
    private String storeDescription;

    @Column(name = "primary_color", nullable = false)
    @Builder.Default
    private String primaryColor = "#E91E63";

    @Column(name = "secondary_color", nullable = false)
    @Builder.Default
    private String secondaryColor = "#9C27B0";

    @Column(name = "accent_color", nullable = false)
    @Builder.Default
    private String accentColor = "#FFD700";

    @Column(name = "dark_color", nullable = false)
    @Builder.Default
    private String darkColor = "#1F2937";

    @Column(name = "show_hero", nullable = false)
    @Builder.Default
    private boolean showHero = true;

    @Column(name = "hero_autoplay_speed", nullable = false)
    @Builder.Default
    private int heroAutoplaySpeed = 5000;

    @Column(name = "show_categories", nullable = false)
    @Builder.Default
    private boolean showCategories = true;

    @Column(name = "categories_title")
    private String categoriesTitle;

    @Column(name = "categories_limit", nullable = false)
    @Builder.Default
    private int categoriesLimit = 6;

    @Column(name = "show_new_products", nullable = false)
    @Builder.Default
    private boolean showNewProducts = true;

    @Column(name = "new_products_title")
    private String newProductsTitle;

    @Column(name = "new_products_limit", nullable = false)
    @Builder.Default
    private int newProductsLimit = 12;

    @Column(name = "new_products_days", nullable = false)
    @Builder.Default
    private int newProductsDays = 30;

    @Column(name = "show_featured_products", nullable = false)
    @Builder.Default
    private boolean showFeaturedProducts = true;

    @Column(name = "featured_products_title")
    private String featuredProductsTitle;

    @Column(name = "featured_products_limit", nullable = false)
    @Builder.Default
    private int featuredProductsLimit = 12;

    @Column(name = "show_deals", nullable = false)
    @Builder.Default
    private boolean showDeals = true;

    @Column(name = "deals_title")
    private String dealsTitle;

    @Column(name = "deals_limit", nullable = false)
    @Builder.Default
    private int dealsLimit = 12;

    @Column(name = "show_benefits", nullable = false)
    @Builder.Default
    private boolean showBenefits = true;

    @Column(name = "contact_email")
    private String contactEmail;

    @Column(name = "contact_phone")
    private String contactPhone;

    @Column(name = "whatsapp_number")
    private String whatsappNumber;

    @Column(name = "facebook_url")
    private String facebookUrl;

    @Column(name = "instagram_url")
    private String instagramUrl;

    @Column(name = "twitter_url")
    private String twitterUrl;

    @Column(name = "tiktok_url")
    private String tiktokUrl;

    @Column(name = "meta_title")
    private String metaTitle;

    @Column(name = "meta_description", columnDefinition = "text")
    private String metaDescription;

    @Column(name = "meta_keywords", columnDefinition = "text")
    private String metaKeywords;

    @Column(name = "benefit_1_icon")
    private String benefit1Icon;

    @Column(name = "benefit_1_title")
    private String benefit1Title;

    @Column(name = "benefit_1_description", columnDefinition = "text")
    private String benefit1Description;

    @Column(name = "benefit_2_icon")
    private String benefit2Icon;

    @Column(name = "benefit_2_title")
    private String benefit2Title;

    @Column(name = "benefit_2_description", columnDefinition = "text")
    private String benefit2Description;

    @Column(name = "benefit_3_icon")
    private String benefit3Icon;

    @Column(name = "benefit_3_title")
    private String benefit3Title;

    @Column(name = "benefit_3_description", columnDefinition = "text")
    private String benefit3Description;

    @Column(name = "benefit_4_icon")
    private String benefit4Icon;

    @Column(name = "benefit_4_title")
    private String benefit4Title;

    @Column(name = "benefit_4_description", columnDefinition = "text")
    private String benefit4Description;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private Instant updatedAt;
}
