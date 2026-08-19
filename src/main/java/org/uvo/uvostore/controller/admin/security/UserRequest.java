package org.uvo.uvostore.controller.admin.security;

import org.springframework.web.multipart.MultipartFile;

public record UserRequest(
        String name,
        String email,
        String phone,
        String password,
        Long roleId,
        boolean active,
        String notes,
        boolean sendInvitation,
        MultipartFile avatar
) {
}
