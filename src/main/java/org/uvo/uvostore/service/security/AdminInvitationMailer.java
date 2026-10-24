package org.uvo.uvostore.service.security;

import io.sentry.Sentry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.uvo.uvostore.entity.security.User;
import org.uvo.uvostore.entity.tenant.Store;
import org.uvo.uvostore.service.notification.EmailService;
import org.uvo.uvostore.service.url.StorePublicUrlResolver;

import java.time.Instant;
import java.util.UUID;

/**
 * PROD-06. La invitación de un administrador: el token de un solo uso y el correo con el enlace para elegir
 * contraseña.
 *
 * <p>Vivía dentro de {@code UserServiceImpl}. POS-6 lo saca a un componente porque el alta de una tienda invita
 * también a su dueño, y lo hace desde la ruta de plataforma, donde no hay {@code TenantContext}: todo aquí recibe
 * la tienda explícita y no lee el contexto.
 */
@Component
public class AdminInvitationMailer {

    private static final Logger log = LoggerFactory.getLogger(AdminInvitationMailer.class);

    private final EmailService emailService;
    private final StorePublicUrlResolver publicUrls;
    private final AdminInvitationPolicy invitationPolicy;

    public AdminInvitationMailer(EmailService emailService, StorePublicUrlResolver publicUrls,
                                 AdminInvitationPolicy invitationPolicy) {
        this.emailService = emailService;
        this.publicUrls = publicUrls;
        this.invitationPolicy = invitationPolicy;
    }

    /**
     * Un token de un solo uso y la fecha desde la que cuenta su vigencia.
     *
     * <p>Dos UUID sin guiones: 64 caracteres, porque esto viaja en una URL y es lo único que separa a un
     * desconocido del panel. Reescribirlo es lo que invalida el anterior.
     */
    public void issue(User user) {
        user.setInvitationToken(UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", ""));
        user.setInvitationSentAt(Instant.now());
    }

    /**
     * El correo con el enlace para elegir contraseña.
     *
     * <p>Los fallos se registran y no se propagan <b>a propósito</b>: el administrador ya está creado y su
     * token sigue siendo válido, así que tumbar el alta por un SMTP caído sería perder trabajo bueno. Si el
     * correo no sale queda en el log y en Sentry; el invitado puede usar «¿Olvidaste tu contraseña?», que
     * cierra la invitación pendiente, y otro administrador de la tienda puede reenviarla.
     */
    public void send(User user, Store store) {
        try {
            emailService.send(user.getEmail(), "Te invitaron a administrar " + store.getName(), body(user, store));
        } catch (Exception e) {
            log.error("Error enviando la invitación de administrador user_id={} error={}", user.getId(), e.getMessage());
            Sentry.captureException(e);
        }
    }

    /**
     * Visible en el paquete para poder afirmar sobre el cuerpo sin enviar nada: en los tests no hay SMTP y
     * {@code EmailServiceImpl} registra y omite el envío. Mismo recurso que
     * {@code OrderEmailComposer.invitationBody} y {@code MercadoPagoServiceImpl.buildPreferenceRequest}.
     */
    String body(User user, Store store) {
        return "Hola " + user.getName() + ",\n\n"
                + "Te dieron acceso al panel de administración de " + store.getName() + ". "
                + "Elige tu contraseña aquí para entrar:\n\n"
                // PROD-04: el panel de SU tienda. Con la URL global, invitar a un administrador de la
                // tienda B le mandaba al panel de la tienda A, donde su token no vale nada porque el
                // endpoint de aceptación resuelve el tenant por el Host.
                + publicUrls.storefrontUrl(store, "/admin/aceptar-invitacion?token=" + user.getInvitationToken()) + "\n\n"
                + "El enlace es de un solo uso y caduca en " + invitationPolicy.ttlDays() + " días. "
                + "Nadie más conoce tu contraseña: la eliges tú.";
    }
}
