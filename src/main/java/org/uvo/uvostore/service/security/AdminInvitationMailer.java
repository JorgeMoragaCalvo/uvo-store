package org.uvo.uvostore.service.security;

import org.springframework.stereotype.Component;
import org.uvo.uvostore.entity.security.User;
import org.uvo.uvostore.entity.tenant.Store;
import org.uvo.uvostore.service.outbox.EmailDraft;
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
 *
 * <p>IMP-06. Ya no envía: quien emite el token encola el correo en el outbox en la misma transacción
 * ({@code OutboxService.enqueueAdminInvitation}), y {@code AdminInvitationEmailHandler} lo arma con {@link #compose}
 * al entregarlo, después de comprobar que el token sigue siendo el vigente.
 */
@Component
public class AdminInvitationMailer {

    private final StorePublicUrlResolver publicUrls;
    private final AdminInvitationPolicy invitationPolicy;

    public AdminInvitationMailer(StorePublicUrlResolver publicUrls, AdminInvitationPolicy invitationPolicy) {
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

    /** El correo con el enlace para elegir contraseña, con el token vigente del usuario. */
    public EmailDraft compose(User user, Store store) {
        return EmailDraft.of(user.getEmail(), "Te invitaron a administrar " + store.getName(), body(user, store));
    }

    /**
     * Visible en el paquete para poder afirmar sobre el cuerpo sin enviar nada. Mismo recurso que
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
