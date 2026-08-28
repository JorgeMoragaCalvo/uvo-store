package org.uvo.uvostore.auth;

import org.junit.jupiter.api.Test;
import org.uvo.uvostore.entity.security.User;
import org.uvo.uvostore.entity.tenant.Store;
import org.uvo.uvostore.support.IntegrationTestSupport;

import java.time.Instant;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// /api/admin/auth/forgot-password + reset-password. No SMTP is configured in tests, so
// EmailServiceImpl logs and skips the actual send (see its class comment) — these tests read the
// token straight from the DB instead of from an inbox, same as reading it from the email link
// would in production.
class PasswordResetTest extends IntegrationTestSupport {

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

        mockMvc.perform(post("/api/admin/auth/forgot-password")
                        .header("Host", hostHeader(store))
                        .contentType("application/json")
                        .content("{\"email\":\"" + admin.getEmail() + "\"}"))
                .andExpect(status().isOk());

        User refreshed = userRepository.findById(admin.getId()).orElseThrow();
        String token = refreshed.getPasswordResetToken();
        org.junit.jupiter.api.Assertions.assertNotNull(token);

        mockMvc.perform(post("/api/admin/auth/reset-password")
                        .contentType("application/json")
                        .content("{\"token\":\"" + token + "\",\"password\":\"nueva-clave-123\"}"))
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
        mockMvc.perform(post("/api/admin/auth/reset-password")
                        .contentType("application/json")
                        .content("{\"token\":\"" + token + "\",\"password\":\"otra-clave-456\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void resetPasswordRejectsAnInvalidToken() throws Exception {
        mockMvc.perform(post("/api/admin/auth/reset-password")
                        .contentType("application/json")
                        .content("{\"token\":\"no-such-token\",\"password\":\"nueva-clave-123\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void resetPasswordRejectsAnExpiredToken() throws Exception {
        Store store = createStore("reset-expired");
        User admin = createAdmin(store, "reset-expired-admin");
        admin.setPasswordResetToken("expired-token-" + nextSeq());
        admin.setPasswordResetExpiresAt(Instant.now().minusSeconds(60));
        userRepository.save(admin);

        mockMvc.perform(post("/api/admin/auth/reset-password")
                        .contentType("application/json")
                        .content("{\"token\":\"" + admin.getPasswordResetToken() + "\",\"password\":\"nueva-clave-123\"}"))
                .andExpect(status().isUnauthorized());
    }
}
