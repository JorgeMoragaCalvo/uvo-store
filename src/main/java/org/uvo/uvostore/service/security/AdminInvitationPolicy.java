package org.uvo.uvostore.service.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.uvo.uvostore.entity.security.User;

import java.time.Duration;
import java.time.Instant;

/**
 * PROD-06. El único sitio que decide cuánto vive una invitación de administrador y cada cuánto se puede
 * reenviar.
 *
 * <p>Nace porque {@code ttl-days} ya se leía en dos clases ({@code AuthController} para validar la
 * aceptación y {@code UserServiceImpl} para escribirlo en el correo) y el panel necesitaba una tercera
 * copia para poder decir «caducada». Tres lecturas de la misma propiedad es como se desincronizan: el
 * correo prometiendo siete días, el endpoint aceptando cinco y la pantalla dibujando otra cosa. Mismo
 * criterio que {@code SettingValues} con el dinero y {@code ReportZone} con la zona horaria.
 *
 * <p>La vigencia se cuenta desde {@code invitation_sent_at}, que es también lo que el reenvío reescribe,
 * así que reenviar extiende el plazo sin ninguna columna más.
 */
@Component
public class AdminInvitationPolicy {

    private final Duration ttl;
    private final Duration resendCooldown;

    public AdminInvitationPolicy(
            // Más corta que los 30 días de la invitación de cliente (F24): al otro lado de este enlace hay
            // reembolsos y credenciales de pasarela.
            @Value("${app.admin-invitation.ttl-days:7}") int ttlDays,
            @Value("${app.admin-invitation.resend-cooldown-minutes:5}") int resendCooldownMinutes) {
        this.ttl = Duration.ofDays(ttlDays);
        this.resendCooldown = Duration.ofMinutes(resendCooldownMinutes);
    }

    public long ttlDays() {
        return ttl.toDays();
    }

    /** Cuándo deja de valer la invitación en curso, o {@code null} si nunca se envió ninguna. */
    public Instant expiresAt(User user) {
        return user.getInvitationSentAt() == null ? null : user.getInvitationSentAt().plus(ttl);
    }

    /** Si la invitación sigue dentro de plazo. Sin fecha de envío no hay invitación válida que aceptar. */
    public boolean isWithinTtl(User user) {
        Instant expiresAt = expiresAt(user);
        return expiresAt != null && expiresAt.isAfter(Instant.now());
    }

    /**
     * Lo que falta para poder reenviar, o {@code null} si ya se puede.
     *
     * <p>Se redondea hacia arriba porque el mensaje lo lee una persona: decir «espera 0 minutos» cuando
     * faltan cuarenta segundos es peor que decir «espera 1 minuto».
     */
    public Duration resendCooldownRemaining(User user) {
        Instant sentAt = user.getInvitationSentAt();
        if (sentAt == null) {
            return null;
        }
        Instant readyAt = sentAt.plus(resendCooldown);
        Instant now = Instant.now();
        return readyAt.isAfter(now) ? Duration.between(now, readyAt) : null;
    }
}
