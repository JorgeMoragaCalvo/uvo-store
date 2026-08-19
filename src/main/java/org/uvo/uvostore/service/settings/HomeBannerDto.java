package org.uvo.uvostore.service.settings;

public record HomeBannerDto(
        Long id,
        String title,
        String subtitle,
        String description,
        String image,
        String mobileImage,
        String ctaText,
        String ctaLink,
        boolean ctaNewTab,
        String ctaSecondaryText,
        String ctaSecondaryLink,
        String textPosition,
        String textColor,
        String overlayColor,
        int overlayOpacity,
        boolean active,
        int sortOrder
) {
}
