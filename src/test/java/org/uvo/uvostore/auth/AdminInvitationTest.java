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
 * El administrador invitado elige su propia contraseña.
 *
 * <p>Antes la única forma de crear un administrador era que quien lo creaba <b>le inventara la clave</b> y
 * se la pasara por fuera: el creador la conocía para siempre, nada obligaba a cambiarla y nada registraba
 * si se había cambiado. El aparato de invitación existía a medias —token UNIQUE desde V4,
 * {@code invitation_sent_at}, un {@code invitation_accepted_at} que no se escribía nunca, y el
 * {@code sendInvitation} de {@code createUser}— pero no había correo, ni endpoint, ni forma de pedirlo
 * desde el panel.
 *
 * <p>El token se lee de la base, no de un buzón: en los tests no hay SMTP y {@code EmailServiceImpl}
 * registra y omite el envío, igual que en {@code PasswordResetTest} y {@code CustomerInvitationTest}.
 */
class AdminInvitationTest extends IntegrationTestSupport {

    private static final String CHOSEN_PASSWORD = "la-elijo-yo-12345";

    @Autowired
    private UserRepository userRepository;
    @PersistenceContext
    private EntityManager entityManager;

    @Test
    @DisplayName("Crear con invitación deja la cuenta sin contraseña, y no se puede entrar")
    void invitingLeavesTheAccountWithoutAPassword() throws Exception {
        Fixture f = fixture("inv-admin-new");

        String email = "invitado-" + nextSeq() + "@test.local";
        mockMvc.perform(createUser(f, email, null, true)).andExpect(status().isOk());

        User invited = userRepository.findByStoreIdAndEmail(f.store.getId(), email).orElseThrow();
        assertThat(invited.getPassword()).as("nadie más llega a conocer su clave").isNull();
        assertThat(invited.getInvitationToken()).isNotBlank();
        assertThat(invited.getInvitationSentAt()).isNotNull();
        assertThat(invited.getInvitationAcceptedAt()).isNull();

        // Inerte hasta que su dueño la active: matches(raw, null) devuelve false.
        mockMvc.perform(login(f.store, email, CHOSEN_PASSWORD)).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Aceptar la invitación fija la clave, consume el token y deja constancia")
    void acceptingSetsThePasswordAndRecordsIt() throws Exception {
        Fixture f = fixture("inv-admin-accept");
        User invited = invite(f);

        mockMvc.perform(accept(f.store, invited.getInvitationToken(), CHOSEN_PASSWORD))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(invited.getEmail()))
                .andExpect(jsonPath("$.type").value("ADMIN"));

        // flush ANTES de clear: clear() descarta los cambios pendientes del contexto, así que sin volcar
        // primero se perdería lo que acaba de escribir el endpoint y la fila se leería como estaba antes.
        entityManager.flush();
        entityManager.clear();
        User active = userRepository.findById(invited.getId()).orElseThrow();
        assertThat(active.getPassword()).isNotNull();
        assertThat(active.getInvitationToken()).as("de un solo uso").isNull();
        // La columna que existía desde V4 y no se escribía nunca: ahora se puede saber si este
        // administrador llegó a poner una clave suya.
        assertThat(active.getInvitationAcceptedAt()).isNotNull();
    }

    @Test
    @DisplayName("La sesión que devuelve trae los permisos del rol, no una sesión pelada")
    void theReturnedSessionCarriesThePermissions() throws Exception {
        Fixture f = fixture("inv-admin-perms");
        User invited = invite(f);

        // El panel dibuja el menú con los permisos del token (A1); sin ellos el invitado entraría a un
        // panel vacío, que es el síntoma que F08 describió.
        mockMvc.perform(accept(f.store, invited.getInvitationToken(), CHOSEN_PASSWORD))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.permissions").isArray())
                .andExpect(jsonPath("$.permissions", org.hamcrest.Matchers.hasItem("products.view")));
    }

    @Test
    @DisplayName("Y después el login normal funciona con la contraseña elegida")
    void theNormalLoginWorksAfterwards() throws Exception {
        Fixture f = fixture("inv-admin-login");
        User invited = invite(f);

        mockMvc.perform(accept(f.store, invited.getInvitationToken(), CHOSEN_PASSWORD))
                .andExpect(status().isOk());

        mockMvc.perform(login(f.store, invited.getEmail(), CHOSEN_PASSWORD))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty());
    }

    @Test
    @DisplayName("El token no sirve dos veces")
    void theTokenIsSingleUse() throws Exception {
        Fixture f = fixture("inv-admin-once");
        User invited = invite(f);
        String token = invited.getInvitationToken();

        mockMvc.perform(accept(f.store, token, CHOSEN_PASSWORD)).andExpect(status().isOk());

        mockMvc.perform(accept(f.store, token, "otra-clave-valida-123"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("La invitación no es válida o ha expirado"));
    }

    @Test
    @DisplayName("Una invitación caducada se rechaza")
    void anExpiredInvitationIsRejected() throws Exception {
        Fixture f = fixture("inv-admin-expired");
        User invited = invite(f);

        // El TTL por defecto son 7 días: se retrasa la fecha de envío con SQL nativo porque la pone el
        // propio alta (mismo recurso que en F20 y F24).
        entityManager.createNativeQuery("UPDATE users SET invitation_sent_at = :sentAt WHERE id = :id")
                .setParameter("sentAt", Instant.now().minus(8, ChronoUnit.DAYS))
                .setParameter("id", invited.getId())
                .executeUpdate();
        entityManager.flush();
        entityManager.clear();

        mockMvc.perform(accept(f.store, invited.getInvitationToken(), CHOSEN_PASSWORD))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("La invitación no es válida o ha expirado"));
    }

    @Test
    @DisplayName("Un token inventado, o de otra tienda, se rechaza igual")
    void unknownOrForeignTokensAreRejected() throws Exception {
        Fixture f = fixture("inv-admin-foreign");
        Fixture other = fixture("inv-admin-other");
        User invited = invite(f);

        mockMvc.perform(accept(f.store, "token-que-no-existe", CHOSEN_PASSWORD))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("La invitación no es válida o ha expirado"));

        // Mismo token, canjeado desde el dominio de otra tienda.
        mockMvc.perform(accept(other.store, invited.getInvitationToken(), CHOSEN_PASSWORD))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Sin invitación y sin contraseña se rechaza en español, no con el mensaje de BCrypt")
    void creatingWithoutInvitationNorPasswordIsRejected() throws Exception {
        Fixture f = fixture("inv-admin-nopass");

        // Antes esto llegaba a passwordEncoder.encode(null) y salía un 400 con "rawPassword cannot be
        // null" — una regla que no existía, cumplida por accidente.
        mockMvc.perform(createUser(f, "sinclave-" + nextSeq() + "@test.local", null, false))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Indica una contraseña o activa el envío de invitación"));
    }

    @Test
    @DisplayName("Sin invitación y con contraseña sigue funcionando como siempre")
    void creatingWithAPasswordStillWorks() throws Exception {
        Fixture f = fixture("inv-admin-direct");
        String email = "directo-" + nextSeq() + "@test.local";

        mockMvc.perform(createUser(f, email, "clave-directa-123", false)).andExpect(status().isOk());

        User created = userRepository.findByStoreIdAndEmail(f.store.getId(), email).orElseThrow();
        assertThat(created.getPassword()).isNotNull();
        assertThat(created.getInvitationToken()).as("sin invitación no hay token").isNull();
        mockMvc.perform(login(f.store, email, "clave-directa-123")).andExpect(status().isOk());
    }

    // --- fixtures ----------------------------------------------------------------------------------

    private record Fixture(Store store, String token, Long roleId) {
    }

    private Fixture fixture(String prefix) throws Exception {
        Store store = createStore(prefix);
        User admin = createAdmin(store, prefix);
        String token = loginAdmin(store, admin);
        // El rol del admin creador sirve para darle permisos al invitado y comprobar que viajan en su
        // sesión.
        Long roleId = admin.getRoles().iterator().next().getId();
        return new Fixture(store, token, roleId);
    }

    /** Crea un administrador por invitación y devuelve su fila, con el token que viajaría en el correo. */
    private User invite(Fixture f) throws Exception {
        String email = "invitado-" + nextSeq() + "@test.local";
        mockMvc.perform(createUser(f, email, null, true)).andExpect(status().isOk());
        // Ídem: sin el flush se perdería la asignación de roles que hizo createUser, y el invitado llegaría
        // al test sin permisos.
        entityManager.flush();
        entityManager.clear();
        return userRepository.findByStoreIdAndEmail(f.store.getId(), email).orElseThrow();
    }

    private org.springframework.test.web.servlet.RequestBuilder createUser(
            Fixture f, String email, String password, boolean sendInvitation) {
        var request = multipart("/api/admin/users")
                .header("Host", hostHeader(f.store))
                .header("Authorization", "Bearer " + f.token)
                .param("name", "Invitado")
                .param("email", email)
                .param("active", "true")
                .param("roleId", String.valueOf(f.roleId))
                .param("sendInvitation", String.valueOf(sendInvitation));
        return password == null ? request : request.param("password", password);
    }

    private org.springframework.test.web.servlet.RequestBuilder accept(Store store, String token, String password) {
        return post("/api/admin/auth/accept-invitation")
                .header("Host", hostHeader(store))
                .contentType("application/json")
                .content("""
                        {"token":"%s","password":"%s"}""".formatted(token, password));
    }

    private org.springframework.test.web.servlet.RequestBuilder login(Store store, String email, String password) {
        return post("/api/admin/auth/login")
                .header("Host", hostHeader(store))
                .contentType("application/json")
                .content("""
                        {"email":"%s","password":"%s"}""".formatted(email, password));
    }
}
