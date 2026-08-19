package org.uvo.uvostore.controller.settings;

import org.springframework.web.multipart.MultipartFile;

public record HomeBannerRequest(
        String title,
        String subtitle,
        String description,
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
        int sortOrder,
        MultipartFile newImage,
        MultipartFile newMobileImage
) {
}
