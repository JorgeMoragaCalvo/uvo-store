package org.uvo.uvostore.service.catalog;

public record ProductImageDto(Long id, String url, String thumbnail, String alt, boolean isFeatured) {
}
