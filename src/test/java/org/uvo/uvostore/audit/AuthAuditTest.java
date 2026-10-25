package org.uvo.uvostore.audit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.uvo.uvostore.entity.security.User;
import org.uvo.uvostore.entity.tenant.Store;
import org.uvo.uvostore.support.AuditRows;
import org.uvo.uvostore.support.IntegrationTestSupport;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * PROD-18. En el login, la invitación y el restablecimiento el actor es la cuenta, no el contexto de seguridad: el
 * panel manda en esas rutas el Bearer que tenga guardado, y puede ser de otro admin. Y el restablecimiento queda en la
 * tienda del usuario, porque busca el token sin mirar el Host.
 */
class AuthAuditTest extends IntegrationTestSupport {

    private static final String NEW_PASSWORD = "ClaveNueva-2026x";

    @Test
    @DisplayName("Entrar como B con el Bearer viejo de A deja el login a nombre de B")
    void aLoginIsAttributedToTheAccountNotToTheStaleBearer() throws Exception {
        Store store = createStore("audit-stale");
        User adminA = createAdmin(store, "audit-stale-a");
        User adminB = createAdmin(store, "audit-stale-b");
        String tokenA = loginAdmin(store, adminA);

        mockMvc.perform(post("/api/admin/auth/login")
                        .header("Host", hostHeader(store))
                        .header("Authorization", "Bearer " + tokenA)
                        .contentType("application/json")
                        .content("{\"email\":\"" + adminB.getEmail() + "\",\"password\":\"" + TEST_PASSWORD + "\"}"))
                .andExpect(status().isOk());

        assertThat(auditRows(store, "AUTH_LOGIN"))
                .filteredOn(row -> adminB.getId().equals(row.targetId()))
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.actorId()).isEqualTo(adminB.getId());
                    assertThat(row.actorLabel()).isEqualTo(adminB.getEmail());
                    assertThat(row.result()).isEqualTo("SUCCESS");
                });
    }

    @Test
    @DisplayName("Aceptar una invitación queda a nombre del invitado")
    void acceptingAnInvitationIsAttributedToTheInvitee() throws Exception {
        Store store = createStore("audit-accept");
        User admin = createAdmin(store, "audit-accept");
        String token = loginAdmin(store, admin);
        String email = "invitado-" + nextSeq() + "@test.local";
        mockMvc.perform(multipart("/api/admin/users")
                        .header("Host", hostHeader(store))
                        .header("Authorization", "Bearer " + token)
                        .param("name", "Invitado")
                        .param("email", email)
                        .param("active", "true")
                        .param("sendInvitation", "true"))
                .andExpect(status().isOk());
        User invited = userRepository.findByStoreIdAndEmail(store.getId(), email).orElseThrow();

        mockMvc.perform(post("/api/admin/auth/accept-invitation")
                        .header("Host", hostHeader(store))
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content("{\"token\":\"" + invited.getInvitationToken() + "\",\"password\":\"" + NEW_PASSWORD + "\"}"))
                .andExpect(status().isOk());

        AuditRows.Row row = auditRow(store, "USER_INVITATION_ACCEPT");
        assertThat(row.actorType()).isEqualTo("ADMIN");
        assertThat(row.actorId()).isEqualTo(invited.getId());
        assertThat(row.targetId()).isEqualTo(invited.getId());
    }

    @Test
    @DisplayName("Un restablecimiento sin Host queda en la tienda del usuario, a su nombre")
    void aResetWithoutHostIsRecordedInTheUsersStore() throws Exception {
        Store store = createStore("audit-reset");
        User admin = createAdmin(store, "audit-reset");
        String resetToken = "reset-" + nextSeq();
        admin.setPasswordResetToken(resetToken);
        admin.setPasswordResetExpiresAt(Instant.now().plus(1, ChronoUnit.HOURS));
        userRepository.save(admin);

        mockMvc.perform(post("/api/admin/auth/reset-password")
                        .contentType("application/json")
                        .content("{\"token\":\"" + resetToken + "\",\"password\":\"" + NEW_PASSWORD + "\"}"))
                .andExpect(status().isOk());

        AuditRows.Row row = auditRow(store, "USER_PASSWORD_RESET");
        assertThat(row.actorId()).isEqualTo(admin.getId());
        assertThat(row.json().path("invitationClosed").asBoolean()).isFalse();
        assertThat(row.details()).doesNotContain(resetToken, NEW_PASSWORD);
    }
}
