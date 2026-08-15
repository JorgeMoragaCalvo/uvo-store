package org.uvo.uvostore.entity.settings;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
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
import org.uvo.uvostore.entity.settings.enums.TextColor;
import org.uvo.uvostore.entity.settings.enums.TextPosition;

@Entity
@Table(name = "home_banners")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class HomeBanner {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @EqualsAndHashCode.Include
    private Long id;

    @Column(nullable = false)
    private String title;

    private String subtitle;

    @Column(columnDefinition = "text")
    private String description;

    @Column(nullable = false)
    private String image;

    @Column(name = "mobile_image")
    private String mobileImage;

    @Column(name = "cta_text", nullable = false)
    @Builder.Default
    private String ctaText = "Ver Más";

    @Column(name = "cta_link")
    private String ctaLink;

    @Column(name = "cta_new_tab", nullable = false)
    @Builder.Default
    private boolean ctaNewTab = false;

    @Column(name = "cta_secondary_text")
    private String ctaSecondaryText;

    @Column(name = "cta_secondary_link")
    private String ctaSecondaryLink;

    @Enumerated(EnumType.STRING)
    @Column(name = "text_position", nullable = false)
    @Builder.Default
    private TextPosition textPosition = TextPosition.LEFT;

    @Enumerated(EnumType.STRING)
    @Column(name = "text_color", nullable = false)
    @Builder.Default
    private TextColor textColor = TextColor.LIGHT;

    @Column(name = "overlay_color")
    private String overlayColor;

    @Column(name = "overlay_opacity", nullable = false)
    @Builder.Default
    private int overlayOpacity = 40;

    @Column(nullable = false)
    @Builder.Default
    private boolean active = true;

    @Column(name = "sort_order", nullable = false)
    @Builder.Default
    private int sortOrder = 0;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private Instant updatedAt;
}
