package org.uvo.uvostore.controller.platform;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record StoreOnboardingRequest(
        @NotBlank @Pattern(regexp = "^[a-z0-9]([a-z0-9-]{1,61}[a-z0-9])?$",
                message = "El nick solo puede contener minúsculas, números y guiones") String slug,
        @Pattern(regexp = "^$|^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)+$",
                message = "El dominio no tiene un formato válido") String domain,
        @NotBlank String storeName,
        @NotBlank String adminName,
        @NotBlank @Email String adminEmail,
        @NotBlank @Size(min = 8, message = "La contraseña debe tener al menos 8 caracteres") String adminPassword
) {
}
