package org.uvo.uvostore.service.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.uvo.uvostore.entity.security.User;
import org.uvo.uvostore.entity.tenant.Store;
import org.uvo.uvostore.support.IntegrationTestSupport;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * El cuerpo del correo de invitación de administrador.
 *
 * <p>En este paquete porque {@code invitationBody(...)} es visible solo en el paquete — la misma razón por
 * la que {@code MercadoPagoPreferenceTest} vive junto a su servicio y por la que el test del cuerpo de la
 * invitación de cliente vive junto a su oyente. Es lo único que se puede acreditar sobre el envío: en los
 * tests no hay SMTP y {@code EmailServiceImpl} registra y omite.
 */
class AdminInvitationEmailTest extends IntegrationTestSupport {

    @Autowired
    private UserServiceImpl userService;

    @Test
    @DisplayName("El enlace lleva el token, y el correo dice a qué tienda y que la clave la elige él")
    void theBodyCarriesTheActivationLink() {
        Store store = createStore("adm-inv-body");
        User user = createAdmin(store, "adm-inv-body");
        user.setInvitationToken("token-de-prueba-0123456789");

        String body = userService.invitationBody(user, store);

        // Sin el token el correo no sirve para nada.
        assertThat(body).contains("token-de-prueba-0123456789");
        assertThat(body).contains("/admin/aceptar-invitacion?token=");
        assertThat(body).contains(store.getName());
        // El texto tiene que decir lo que hace distinta a esta vía: la clave no la pone quien te dio de
        // alta.
        assertThat(body).contains("la eliges tú");
        assertThat(body).contains("un solo uso");
        // PROD-04: al panel de SU tienda.
        assertThat(body).contains("http://" + hostHeader(store) + "/admin/aceptar-invitacion?token=");
    }

    @Test
    @DisplayName("PROD-04: dos tiendas, dos paneles distintos en el enlace")
    void eachStoreGetsItsOwnPanelLink() {
        // Con la URL global, invitar a un administrador de la tienda B le mandaba al panel de la tienda A,
        // donde su token no vale nada: adminAcceptInvitation resuelve el tenant por el Host.
        Store first = createStore("adm-inv-host-a");
        Store second = createStore("adm-inv-host-b");
        User firstUser = createAdmin(first, "adm-inv-host-a");
        User secondUser = createAdmin(second, "adm-inv-host-b");
        firstUser.setInvitationToken("token-a");
        secondUser.setInvitationToken("token-b");

        String firstBody = userService.invitationBody(firstUser, first);
        String secondBody = userService.invitationBody(secondUser, second);

        assertThat(firstBody).contains("http://" + hostHeader(first) + "/admin/aceptar-invitacion");
        assertThat(secondBody).contains("http://" + hostHeader(second) + "/admin/aceptar-invitacion");
        assertThat(firstBody).doesNotContain(hostHeader(second));
    }

    @Test
    @DisplayName("PROD-04: un dominio propio verificado manda el enlace a ese dominio; sin verificar, no")
    void aVerifiedCustomDomainIsUsedInTheLink() {
        Store store = createStore("adm-inv-dominio");
        User user = createAdmin(store, "adm-inv-dominio");
        user.setInvitationToken("token-de-dominio");
        store.setDomain("tienda-" + nextSeq() + ".example");

        // Sin verificar: el dominio está escrito pero su DNS puede no apuntar aquí todavía, y un correo
        // con ese enlace sería un correo inservible que nadie puede reenviar.
        assertThat(userService.invitationBody(user, store))
                .contains("http://" + hostHeader(store) + "/admin/aceptar-invitacion")
                .doesNotContain(store.getDomain());

        store.setDomainVerifiedAt(java.time.Instant.now());

        assertThat(userService.invitationBody(user, store))
                .contains("http://" + store.getDomain() + "/admin/aceptar-invitacion");
    }
}
