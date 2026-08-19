package org.uvo.uvostore.service.security;

import java.time.Instant;
import java.util.List;

public record UserDto(
        Long id, String name, String email, String phone, String avatar, boolean active,
        Instant lastLoginAt, String notes, List<RoleRefDto> roles, Instant createdAt
) {
}
