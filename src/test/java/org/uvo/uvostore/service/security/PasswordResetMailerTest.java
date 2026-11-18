package org.uvo.uvostore.service.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.uvo.uvostore.entity.tenant.Store;
import org.uvo.uvostore.support.IntegrationTestSupport;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PROD-04. El enlace de recuperación de contraseña lleva al panel de su propia tienda.
 *
 * <p>En este paquete porque {@code PasswordResetMailer.body(...)} es visible solo en el paquete, igual que el
 * cuerpo de la invitación de administrador ({@code AdminInvitationMailerTest}). IMP-06: el cuerpo vivía en
 * {@code AuthController}; ahora lo arma el mailer al entregar la fila del outbox. {@code PasswordResetTest} cubre el recorrido (token, caducidad, un
 * solo uso) leyendo la fila de la base; lo que no se podía comprobar hasta ahora es <b>a dónde apunta el
 * enlace</b>, que con una sola URL global era siempre el mismo panel para todas las tiendas.
 */
class PasswordResetMailerTest extends IntegrationTestSupport {

    @Autowired
    private PasswordResetMailer resetMailer;

    @Test
    @DisplayName("Dos tiendas reciben dos enlaces distintos, cada uno al suyo")
    void eachStoreGetsItsOwnResetLink() {
        Store first = createStore("reset-host-a");
        Store second = createStore("reset-host-b");

        String firstBody = resetMailer.body(first, "token-a");
        String secondBody = resetMailer.body(second, "token-b");

        assertThat(firstBody).contains("http://" + hostHeader(first) + "/admin/reset-password?token=token-a");
        assertThat(secondBody).contains("http://" + hostHeader(second) + "/admin/reset-password?token=token-b");
        // Lo que estaba roto: los dos cuerpos eran idénticos salvo el token.
        assertThat(firstBody).doesNotContain(hostHeader(second));
    }

    @Test
    @DisplayName("Con dominio propio verificado, el enlace va a ese dominio")
    void aVerifiedCustomDomainIsUsed() {
        Store store = createStore("reset-dominio");
        store.setDomain("reset-" + nextSeq() + ".example");
        store.setDomainVerifiedAt(java.time.Instant.now());

        assertThat(resetMailer.body(store, "tok"))
                .contains("http://" + store.getDomain() + "/admin/reset-password?token=tok");
    }
}
