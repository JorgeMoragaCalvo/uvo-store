package org.uvo.uvostore.service.security;

import org.springframework.stereotype.Component;
import org.uvo.uvostore.entity.security.User;
import org.uvo.uvostore.entity.tenant.Store;
import org.uvo.uvostore.service.outbox.EmailDraft;
import org.uvo.uvostore.service.url.StorePublicUrlResolver;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * IMP-06. El token de «¿Olvidaste tu contraseña?» del panel y su correo. Vivían en {@code AuthController}, que enviaba
 * en el hilo de la petición; ahora el controlador emite el token y encola el correo en el outbox
 * ({@code OutboxService.enqueuePasswordReset}), y {@code PasswordResetEmailHandler} lo arma con esto al entregarlo.
 */
@Component
public class PasswordResetMailer {

    /**
     * El enlace vence pronto porque, a diferencia de la invitación, cualquiera que conozca una dirección puede pedirlo
     * una y otra vez.
     */
    public static final Duration TTL = Duration.ofHours(1);

    private final StorePublicUrlResolver publicUrls;

    public PasswordResetMailer(StorePublicUrlResolver publicUrls) {
        this.publicUrls = publicUrls;
    }

    /** Un token nuevo, que deja sin efecto el anterior (la búsqueda es por token). */
    public void issue(User user) {
        user.setPasswordResetToken(UUID.randomUUID().toString());
        user.setPasswordResetExpiresAt(Instant.now().plus(TTL));
    }

    /** El correo con el token vigente del usuario. */
    public EmailDraft compose(User user, Store store) {
        return EmailDraft.of(user.getEmail(), "Recupera tu contraseña", body(store, user.getPasswordResetToken()));
    }

    /**
     * PROD-04. El enlace sale del origen de ESTA tienda. Antes era la URL global de plataforma, así que el
     * administrador de cualquier tienda recibía un enlace al panel de otra — y el token, que sí es suyo, no
     * habría servido allí: {@code adminResetPassword} resuelve el tenant por el {@code Host}.
     */
    String body(Store store, String token) {
        String link = publicUrls.storefrontUrl(store, "/admin/reset-password?token=" + token);
        return "Recibimos una solicitud para restablecer tu contraseña.\n\n"
                + "Ingresa al siguiente enlace para elegir una nueva (válido por 1 hora):\n" + link
                + "\n\nSi no fuiste tú, puedes ignorar este correo.";
    }
}
