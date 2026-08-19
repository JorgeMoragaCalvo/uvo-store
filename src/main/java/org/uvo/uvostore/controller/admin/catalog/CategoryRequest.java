package org.uvo.uvostore.controller.admin.catalog;

import org.springframework.web.multipart.MultipartFile;

public record CategoryRequest(
        String name,
        String description,
        Long parentId,
        boolean active,
        int sortOrder,
        boolean isFeatured,
        String icon,
        MultipartFile image
) {
}
