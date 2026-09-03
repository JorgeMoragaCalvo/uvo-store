package org.uvo.uvostore.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.uvo.uvostore.entity.security.User;
import org.uvo.uvostore.entity.tenant.Store;
import org.uvo.uvostore.support.IntegrationTestSupport;

import java.util.HashMap;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A4. Its own context with tiny limits: the rest of the suite runs with deliberately huge ones set
 * in pom.xml's surefire block, because IntegrationTestSupport.loginAdmin hits the real login
 * endpoint and almost every test calls it — production's 5/min would make the whole suite fail
 * intermittently depending on how many tests happened to run inside the same minute.
 */
@SpringBootTest(properties = {
        "app.rate-limit.login=3",
        "app.rate-limit.window-seconds=60"
})
class RateLimitTest extends IntegrationTestSupport {

    @Test
    @DisplayName("Al superar el límite de intentos de login se responde 429 con Retry-After")
    void loginIsThrottledAfterTheConfiguredNumberOfAttempts() throws Exception {
        Store store = createStore("throttle");
        User admin = createAdmin(store, "throttle");

        // Wrong password on purpose: throttling must not depend on the attempt succeeding, which is
        // the whole point against brute force.
        for (int attempt = 1; attempt <= 3; attempt++) {
            mockMvc.perform(loginRequest(store, "203.0.113.10", admin.getEmail(), "contraseña-incorrecta"))
                    .andExpect(status().isUnauthorized());
        }

        mockMvc.perform(loginRequest(store, "203.0.113.10", admin.getEmail(), "contraseña-incorrecta"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.status").value(429));
    }

    @Test
    @DisplayName("El límite también corta los intentos con la contraseña correcta")
    void throttlingAppliesToValidCredentialsToo() throws Exception {
        Store store = createStore("throttle-ok");
        User admin = createAdmin(store, "throttle-ok");

        for (int attempt = 1; attempt <= 3; attempt++) {
            mockMvc.perform(loginRequest(store, "203.0.113.20", admin.getEmail(), TEST_PASSWORD))
                    .andExpect(status().isOk());
        }

        mockMvc.perform(loginRequest(store, "203.0.113.20", admin.getEmail(), TEST_PASSWORD))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    @DisplayName("Una ruta sin regla no se limita")
    void unthrottledRoutesAreUntouched() throws Exception {
        Store store = createStore("throttle-free");

        for (int i = 0; i < 10; i++) {
            mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                            .get("/api/v1/products")
                            .header("Host", hostHeader(store)))
                    .andExpect(status().isOk());
        }
    }

    private org.springframework.test.web.servlet.RequestBuilder loginRequest(
            Store store, String clientIp, String email, String password) throws Exception {
        String body = objectMapper.writeValueAsString(new HashMap<>() {{
            put("email", email);
            put("password", password);
        }});
        return post("/api/admin/auth/login")
                .header("Host", hostHeader(store))
                // Each test gets its own client IP so they don't share a counter: the filter keys on
                // X-Forwarded-For when present, which is also how it behaves behind a reverse proxy.
                .header("X-Forwarded-For", clientIp)
                .contentType("application/json")
                .content(body);
    }
}
