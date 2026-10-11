package org.uvo.uvostore.auth;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.uvo.uvostore.entity.security.User;
import org.uvo.uvostore.entity.tenant.Store;
import org.uvo.uvostore.repository.UserRepository;
import org.uvo.uvostore.support.IntegrationTestSupport;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * PROD-06. Reenviar una invitación de administrador.
 *
 * <p>Antes no existía: si los 7 días se agotaban, la única salida era <b>borrar al usuario y crearlo otra
 * vez</b>, lo que le cambia el id y pierde sus notas. Y reenviar una invitación caducada es el caso de uso
 * principal, no la excepción — por eso el primer test de aquí es justamente ese.
 *
 * <p>Lo que el criterio pide acreditar: que el reenvío <b>invalida el anterior</b>, que está limitado en
 * frecuencia, y que solo lo puede hacer quien tiene el permiso.
 */
class AdminInvitationResendTest extends IntegrationTestSupport {

    private static final String CHOSEN_PASSWORD = "la-elijo-yo-12345";

    @Autowired
    private UserRepository userRepository;
    @PersistenceContext
    private EntityManager entityManager;

    @Test
    @DisplayName("Reenviar cambia el token: el enlace anterior deja de servir y el nuevo sí sirve")
    void resendingInvalidatesThePreviousLink() throws Exception {
        Fixture f = fixture("resend-nuevo");
        User invited = invite(f);
        String firstToken = invited.getInvitationToken();
        ageInvitation(invited, 10);

        mockMvc.perform(resend(f, invited.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.invitationPending").value(true));
        entityManager.flush();
        entityManager.clear();

        String secondToken = userRepository.findById(invited.getId()).orElseThrow().getInvitationToken();
        assertThat(secondToken).isNotEqualTo(firstToken);

        // Dejar los dos vivos no arreglaría nada: si se reenvía porque el primer correo acabó donde no
        // debía, el enlace viejo es justamente el que hay que matar.
        mockMvc.perform(accept(f.store, firstToken, CHOSEN_PASSWORD))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("La invitación no es válida o ha expirado"));

        mockMvc.perform(accept(f.store, secondToken, CHOSEN_PASSWORD)).andExpect(status().isOk());
    }

    @Test
    @DisplayName("Una invitación caducada se puede reenviar, y entonces vuelve a servir")
    void anExpiredInvitationCanBeResent() throws Exception {
        Fixture f = fixture("resend-caducada");
        User invited = invite(f);

        // Ocho días: el TTL por defecto son siete.
        ageInvitation(invited, 8 * 24 * 60);
        mockMvc.perform(accept(f.store, invited.getInvitationToken(), CHOSEN_PASSWORD))
                .andExpect(status().isBadRequest());

        mockMvc.perform(resend(f, invited.getId())).andExpect(status().isOk());
        entityManager.flush();
        entityManager.clear();

        String fresh = userRepository.findById(invited.getId()).orElseThrow().getInvitationToken();
        mockMvc.perform(accept(f.store, fresh, CHOSEN_PASSWORD)).andExpect(status().isOk());
    }

    @Test
    @DisplayName("Antes del intervalo mínimo se rechaza, y el mensaje dice cuánto falta")
    void resendingTooSoonIsRejected() throws Exception {
        Fixture f = fixture("resend-pronto");
        User invited = invite(f);

        // El alta acaba de mandar el correo, así que el intervalo (5 minutos) está sin cumplir.
        mockMvc.perform(resend(f, invited.getId()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("espera")))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("minuto")));

        // Y el token no se ha tocado: un reenvío rechazado no puede invalidar el enlace que sí era válido.
        entityManager.flush();
        entityManager.clear();
        assertThat(userRepository.findById(invited.getId()).orElseThrow().getInvitationToken())
                .isEqualTo(invited.getInvitationToken());
    }

    @Test
    @DisplayName("Una cuenta que ya tiene contraseña no se puede invitar otra vez")
    void anAccountWithAPasswordCannotBeInvitedAgain() throws Exception {
        Fixture f = fixture("resend-con-clave");
        User invited = invite(f);
        mockMvc.perform(accept(f.store, invited.getInvitationToken(), CHOSEN_PASSWORD)).andExpect(status().isOk());
        entityManager.flush();
        entityManager.clear();

        mockMvc.perform(resend(f, invited.getId()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value("Esta cuenta ya tiene contraseña: usa la recuperación de contraseña"));
    }

    @Test
    @DisplayName("A una cuenta desactivada no se le reenvía: no podría entrar de todas formas")
    void aDeactivatedAccountIsNotResent() throws Exception {
        Fixture f = fixture("resend-inactivo");
        User invited = invite(f);
        ageInvitation(invited, 10);

        mockMvc.perform(post("/api/admin/users/" + invited.getId() + "/toggle-status")
                        .header("Host", hostHeader(f.store))
                        .header("Authorization", "Bearer " + f.token))
                .andExpect(status().isOk());
        entityManager.flush();
        entityManager.clear();

        // Mandar un enlace a una cuenta en la que adminAcceptInvitation no deja entrar sería mentirle al
        // invitado: recibiría el correo y el enlace fallaría sin decirle por qué.
        mockMvc.perform(resend(f, invited.getId()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value("La cuenta está desactivada: actívala antes de reenviar la invitación"));
    }

    @Test
    @DisplayName("Sin users.manage es 403, aunque se pueda ver la lista de usuarios")
    void resendingNeedsTheManagePermission() throws Exception {
        Store store = createStore("resend-permiso");
        User creator = createAdmin(store, "resend-permiso");
        String creatorToken = loginAdmin(store, creator);
        Long roleId = creator.getRoles().iterator().next().getId();
        User invited = invite(new Fixture(store, creatorToken, roleId));

        // Solo lectura: reenviar es dar acceso al panel, que es la misma capacidad que crear.
        User reader = createAdminWithPermissions(store, "resend-lector", "users.view");
        String readerToken = loginAdmin(store, reader);

        mockMvc.perform(post("/api/admin/users/" + invited.getId() + "/resend-invitation")
                        .header("Host", hostHeader(store))
                        .header("Authorization", "Bearer " + readerToken))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("No se reenvía la invitación de otra tienda")
    void aUserFromAnotherStoreIsNotFound() throws Exception {
        Fixture f = fixture("resend-propia");
        Fixture other = fixture("resend-ajena");
        User invited = invite(other);

        mockMvc.perform(resend(f, invited.getId())).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("La lista marca al invitado como pendiente, con su caducidad, y nunca con el token")
    void theListExposesThePendingStateButNeverTheToken() throws Exception {
        Fixture f = fixture("resend-lista");
        User invited = invite(f);

        String body = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/admin/users/" + invited.getId())
                        .header("Host", hostHeader(f.store))
                        .header("Authorization", "Bearer " + f.token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.invitationPending").value(true))
                .andExpect(jsonPath("$.invitationExpiresAt").exists())
                .andReturn().getResponse().getContentAsString();

        // Si el token viajara, leer la lista de usuarios sería una forma de entrar en la cuenta de otro.
        assertThat(body).doesNotContain(invited.getInvitationToken());
    }

    // --- fixtures ----------------------------------------------------------------------------------

    private record Fixture(Store store, String token, Long roleId) {
    }

    private Fixture fixture(String prefix) throws Exception {
        Store store = createStore(prefix);
        User admin = createAdmin(store, prefix);
        String token = loginAdmin(store, admin);
        return new Fixture(store, token, admin.getRoles().iterator().next().getId());
    }

    private User invite(Fixture f) throws Exception {
        String email = "invitado-" + nextSeq() + "@test.local";
        mockMvc.perform(multipart("/api/admin/users")
                        .header("Host", hostHeader(f.store))
                        .header("Authorization", "Bearer " + f.token)
                        .param("name", "Invitado")
                        .param("email", email)
                        .param("active", "true")
                        .param("roleId", String.valueOf(f.roleId))
                        .param("sendInvitation", "true"))
                .andExpect(status().isOk());
        entityManager.flush();
        entityManager.clear();
        return userRepository.findByStoreIdAndEmail(f.store.getId(), email).orElseThrow();
    }

    /**
     * Retrasa el envío de la invitación con SQL nativo, porque la fecha la pone el propio alta. Mismo
     * recurso que el caso de caducidad de {@code AdminInvitationTest} y que los tests de informes.
     */
    private void ageInvitation(User user, long minutes) {
        entityManager.createNativeQuery("UPDATE users SET invitation_sent_at = :sentAt WHERE id = :id")
                .setParameter("sentAt", Instant.now().minus(minutes, ChronoUnit.MINUTES))
                .setParameter("id", user.getId())
                .executeUpdate();
        entityManager.flush();
        entityManager.clear();
    }

    private org.springframework.test.web.servlet.RequestBuilder resend(Fixture f, Long userId) {
        return post("/api/admin/users/" + userId + "/resend-invitation")
                .header("Host", hostHeader(f.store))
                .header("Authorization", "Bearer " + f.token);
    }

    private org.springframework.test.web.servlet.RequestBuilder accept(Store store, String token, String password) {
        return post("/api/admin/auth/accept-invitation")
                .header("Host", hostHeader(store))
                .contentType("application/json")
                .content("""
                        {"token":"%s","password":"%s"}""".formatted(token, password));
    }
}
