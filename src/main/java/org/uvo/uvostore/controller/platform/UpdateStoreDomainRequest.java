package org.uvo.uvostore.controller.platform;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record UpdateStoreDomainRequest(
        @NotBlank @Pattern(regexp = "^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)+$",
                message = "El dominio no tiene un formato válido") String domain
) {
}
