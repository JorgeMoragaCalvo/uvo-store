package org.uvo.uvostore.controller.auth;

public record AuthResponse(String token, Long id, String name, String email, String type) {
}
