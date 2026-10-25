package org.uvo.uvostore.controller.platform;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;

/** PROD-23. Sin correo (o sin cuerpo), la invitación va al correo que ya tiene el dueño. */
public record OwnerAccessRecoveryRequest(@Email @Size(max = 255) String email) {
}
