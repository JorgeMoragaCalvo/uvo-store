package org.uvo.uvostore.service.security;

import org.springframework.web.multipart.MultipartFile;

import java.util.Set;

public record UserCreateCommand(
        String name,
        String email,
        String password,
        String phone,
        boolean isAdmin,
        boolean active,
        String notes,
        boolean sendInvitation,
        MultipartFile avatar,
        Set<Long> roleIds
) {
}
