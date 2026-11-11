package org.uvo.uvostore.auth;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.RequestBuilder;
import org.uvo.uvostore.entity.security.User;
import org.uvo.uvostore.entity.tenant.Store;
import org.uvo.uvostore.support.IntegrationTestSupport;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// /api/admin/auth/forgot-password + reset-password. No SMTP is configured in tests, so
// EmailServiceImpl logs and skips the actual send (see its class comment) — these tests read the
// token straight from the DB instead of from an inbox, same as reading it from the email link
// would in production.
class PasswordResetTest extends IntegrationTestSupport {

    private static final String INVALID_LINK = "El enlace no es válido o ha expirado";

    @Test
    void forgotPasswordAlwaysRespondsOkEvenForAnUnknownEmail() throws Exception {
        Store store = createStore("reset-unknown");

        mockMvc.perform(post("/api/admin/auth/forgot-password")
                        .header("Host", hostHeader(store))
                        .contentType("application/json")
                        .content("{\"email\":\"no-existe-" + nextSeq() + "@test.local\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void forgotThenResetThenLoginWithTheNewPasswordWorks() throws Exception {
        Store store = createStore("reset-flow");
        User admin = createAdmin(store, "reset-admin");

        String token = requestResetToken(store, admin);

        mockMvc.perform(reset(store, token, "nueva-clave-123"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/admin/auth/login")
                        .header("Host", hostHeader(store))
                        .contentType("application/json")
                        .content("{\"email\":\"" + admin.getEmail() + "\",\"password\":\"nueva-clave-123\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").exists());

        // The old password no longer works.
        mockMvc.perform(post("/api/admin/auth/login")
                        .header("Host", hostHeader(store))
                        .contentType("application/json")
                        .content("{\"email\":\"" + admin.getEmail() + "\",\"password\":\"" + TEST_PASSWORD + "\"}"))
                .andExpect(status().isUnauthorized());

        // The token is single-use — reusing it fails even with a fresh valid-looking token value.
        mockMvc.perform(reset(store, token, "otra-clave-456"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void resetPasswordRejectsAnInvalidToken() throws Exception {
        Store store = createStore("reset-invalid");

        mockMvc.perform(reset(store, "no-such-token", "nueva-clave-123"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void resetPasswordRejectsAnExpiredToken() throws Exception {
        Store store = createStore("reset-expired");
        User admin = createAdmin(store, "reset-expired-admin");
        admin.setPasswordResetToken("expired-token-" + nextSeq());
        admin.setPasswordResetExpiresAt(Instant.now().minusSeconds(60));
        userRepository.save(admin);

        mockMvc.perform(reset(store, admin.getPasswordResetToken(), "nueva-clave-123"))
                .andExpect(status().isUnauthorized());
    }

    /**
     * IMP-01 (C06 / GL-01). El token se busca sin mirar la tienda, así que uno vigente de A se podía canjear por el
     * host de B: cambiaba la contraseña del admin de A, consumía el token y cerraba sus sesiones. Ahora B lo rechaza
     * sin tocar nada, y el mismo token sigue sirviendo en A, una sola vez.
     */
    @Test
    void aResetTokenFromAnotherStoreIsRejectedWithoutTouchingTheAccount() throws Exception {
        Store storeA = createStore("reset-cross-a");
        Store storeB = createStore("reset-cross-b");
        User admin = createAdmin(storeA, "reset-cross-admin");
        String session = loginAdmin(storeA, admin);
        String token = requestResetToken(storeA, admin);

        User before = userRepository.findById(admin.getId()).orElseThrow();
        String passwordHash = before.getPassword();
        Instant expiresAt = before.getPasswordResetExpiresAt();
        var tokenVersion = before.getTokenVersion();

        mockMvc.perform(reset(storeB, token, "clave-desde-b-123"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value(INVALID_LINK));

        User after = userRepository.findById(admin.getId()).orElseThrow();
        assertThat(after.getPassword()).isEqualTo(passwordHash);
        assertThat(after.getPasswordResetToken()).isEqualTo(token);
        assertThat(after.getPasswordResetExpiresAt()).isEqualTo(expiresAt);
        assertThat(after.getTokenVersion()).isEqualTo(tokenVersion);
        assertThat(auditRows(storeA, "USER_PASSWORD_RESET")).isEmpty();
        assertThat(auditRows(storeB, "USER_PASSWORD_RESET")).isEmpty();
        // La sesión de A sigue viva: B no la revocó.
        mockMvc.perform(adminRequest(storeA, session)).andExpect(status().isOk());

        // En su tienda el token funciona, revoca las sesiones anteriores y se consume.
        mockMvc.perform(reset(storeA, token, "nueva-clave-123"))
                .andExpect(status().isOk());
        mockMvc.perform(adminRequest(storeA, session)).andExpect(status().isUnauthorized());
        assertThat(auditRows(storeA, "USER_PASSWORD_RESET")).hasSize(1);
        assertThat(passwordEncoder.matches("nueva-clave-123",
                userRepository.findById(admin.getId()).orElseThrow().getPassword())).isTrue();
        mockMvc.perform(reset(storeA, token, "otra-clave-456"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value(INVALID_LINK));
    }

    // IMP-01. Inexistente, vencido o de otra tienda: la misma respuesta, para no contarle a quien prueba tokens en qué
    // se equivocó.
    @Test
    void anUnknownAnExpiredAndAForeignTokenGetTheSameAnswer() throws Exception {
        Store storeA = createStore("reset-same-a");
        Store storeB = createStore("reset-same-b");
        User adminA = createAdmin(storeA, "reset-same-foreign");
        String foreignToken = requestResetToken(storeA, adminA);
        User adminB = createAdmin(storeB, "reset-same-expired");
        adminB.setPasswordResetToken("expired-token-" + nextSeq());
        adminB.setPasswordResetExpiresAt(Instant.now().minusSeconds(60));
        userRepository.save(adminB);

        for (String token : new String[] {"no-such-token", adminB.getPasswordResetToken(), foreignToken}) {
            mockMvc.perform(reset(storeB, token, "nueva-clave-123"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.message").value(INVALID_LINK));
        }
    }

    private String requestResetToken(Store store, User admin) throws Exception {
        mockMvc.perform(post("/api/admin/auth/forgot-password")
                        .header("Host", hostHeader(store))
                        .contentType("application/json")
                        .content("{\"email\":\"" + admin.getEmail() + "\"}"))
                .andExpect(status().isOk());
        String token = userRepository.findById(admin.getId()).orElseThrow().getPasswordResetToken();
        assertThat(token).isNotNull();
        return token;
    }

    private RequestBuilder reset(Store store, String token, String password) {
        return post("/api/admin/auth/reset-password")
                .header("Host", hostHeader(store))
                .contentType("application/json")
                .content("{\"token\":\"" + token + "\",\"password\":\"" + password + "\"}");
    }

    private RequestBuilder adminRequest(Store store, String token) {
        return get("/api/admin/products")
                .header("Host", hostHeader(store))
                .header("Authorization", "Bearer " + token);
    }
}
