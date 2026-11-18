package org.uvo.uvostore.service.outbox;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.uvo.uvostore.entity.security.User;
import org.uvo.uvostore.repository.UserRepository;
import org.uvo.uvostore.service.security.AdminInvitationMailer;
import org.uvo.uvostore.service.security.AdminInvitationPolicy;
import org.uvo.uvostore.service.security.PasswordResetMailer;

import java.time.Instant;

/**
 * IMP-06. Los correos de acceso del panel, armados al entregarlos en una transacción de lectura corta (el envío va
 * después, fuera de ella), como hace {@link OrderEmailComposer} con los de una orden.
 *
 * <p><b>Antes de armar nada comprueba que el acceso sigue siendo el que originó la fila.</b> La fila guarda la huella
 * del token emitido, no el token; si el usuario ya no tiene ese token —se reenvió otra invitación, se usó, se cerró la
 * tienda— o venció, el correo se omite con el motivo. Así un correo atrasado nunca lleva un enlace usable, y un
 * reenvío deja sin efecto el anterior aunque el anterior siga en la cola. Si el token sigue vigente, el correo lleva
 * <b>ese</b> token: un reintento tras un resultado incierto manda el mismo enlace de un solo uso.
 *
 * <p>Los motivos no repiten el token ni la dirección: quedan en {@code last_error}, que ve el panel.
 */
@Component
public class AccessEmailComposer {

    static final String REPLACED = "Reemplazado por un envío posterior: el enlace de esta fila ya no vale";

    private final UserRepository userRepository;
    private final AdminInvitationMailer invitationMailer;
    private final AdminInvitationPolicy invitationPolicy;
    private final PasswordResetMailer resetMailer;

    public AccessEmailComposer(UserRepository userRepository, AdminInvitationMailer invitationMailer,
                               AdminInvitationPolicy invitationPolicy, PasswordResetMailer resetMailer) {
        this.userRepository = userRepository;
        this.invitationMailer = invitationMailer;
        this.invitationPolicy = invitationPolicy;
        this.resetMailer = resetMailer;
    }

    @Transactional(readOnly = true)
    public EmailDraft adminInvitation(Long userId, String tokenHash) {
        User user = userId == null ? null : userRepository.findById(userId).orElse(null);
        if (user == null) {
            return EmailDraft.skip("El usuario ya no existe");
        }
        if (user.getInvitationToken() == null) {
            return EmailDraft.skip("La invitación ya se usó o se cerró");
        }
        if (!AccessTokenHash.matches(user.getInvitationToken(), tokenHash)) {
            return EmailDraft.skip(REPLACED);
        }
        if (!invitationPolicy.isWithinTtl(user)) {
            return EmailDraft.skip("La invitación venció: hay que reenviarla");
        }
        if (!user.isActive()) {
            // adminAcceptInvitation no deja entrar a una cuenta desactivada: el enlace no serviría.
            return EmailDraft.skip("La cuenta está desactivada");
        }
        if (user.getPassword() != null) {
            return EmailDraft.skip("La cuenta ya tiene contraseña");
        }
        return invitationMailer.compose(user, user.getStore());
    }

    @Transactional(readOnly = true)
    public EmailDraft passwordReset(Long userId, String tokenHash) {
        User user = userId == null ? null : userRepository.findById(userId).orElse(null);
        if (user == null) {
            return EmailDraft.skip("El usuario ya no existe");
        }
        if (user.getPasswordResetToken() == null) {
            return EmailDraft.skip("El enlace ya se usó o se anuló");
        }
        if (!AccessTokenHash.matches(user.getPasswordResetToken(), tokenHash)) {
            return EmailDraft.skip(REPLACED);
        }
        if (user.getPasswordResetExpiresAt() == null || !user.getPasswordResetExpiresAt().isAfter(Instant.now())) {
            return EmailDraft.skip("El enlace venció: hay que pedir otro");
        }
        return resetMailer.compose(user, user.getStore());
    }
}
