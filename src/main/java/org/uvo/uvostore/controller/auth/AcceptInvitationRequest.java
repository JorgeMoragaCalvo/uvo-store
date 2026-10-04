package org.uvo.uvostore.controller.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * F24. El cuerpo de {@code POST /api/customer/auth/accept-invitation}.
 *
 * <p>Mismo mínimo de 8 caracteres que {@code ResetPasswordRequest} y {@code CustomerRegisterRequest}: es
 * la contraseña con la que el invitado va a entrar a partir de ahora, no un secreto de usar y tirar.
 */
public record AcceptInvitationRequest(@NotBlank String token, @NotBlank @Size(min = 8) String password) {
}
