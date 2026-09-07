package org.uvo.uvostore.controller.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CustomerRegisterRequest(
        @NotBlank @Email String email,
        // M9: was @NotBlank only, so a one-character password got you an account. Same floor as
        // ResetPasswordRequest and PasswordUpdateRequest, which already required 8.
        @NotBlank @Size(min = 8, message = "La contraseña debe tener al menos 8 caracteres") String password,
        @NotBlank String firstName,
        @NotBlank String lastName,
        String phone
) {
}
