package org.uvo.uvostore.controller.admin.security;

import jakarta.validation.constraints.Size;
import org.springframework.web.multipart.MultipartFile;

public record UserRequest(
        String name,
        String email,
        String phone,
        // M9: no validation at all lived here — and it wouldn't have run anyway, since the
        // controller bound this without @Valid (added there too). Not @NotBlank: on edit an
        // absent password means "keep the current one", and @Size ignores null. Presence on
        // create is enforced in UserServiceImpl, which is the only path that requires it.
        @Size(min = 8, message = "La contraseña debe tener al menos 8 caracteres") String password,
        Long roleId,
        boolean active,
        String notes,
        boolean sendInvitation,
        MultipartFile avatar
) {
}
