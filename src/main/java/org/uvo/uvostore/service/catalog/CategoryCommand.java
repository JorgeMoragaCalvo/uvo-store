package org.uvo.uvostore.service.catalog;

import org.springframework.web.multipart.MultipartFile;

public record CategoryCommand(
        String name, String description, Long parentId, boolean active, int sortOrder,
        boolean isFeatured, String icon, MultipartFile image
) {
}
