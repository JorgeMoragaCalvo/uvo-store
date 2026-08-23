package org.uvo.uvostore.security;

public record AuthPrincipal(Long id, String subject, String type, Long storeId) {
}
