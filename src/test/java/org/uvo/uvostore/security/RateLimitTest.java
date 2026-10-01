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

    @Test
    @DisplayName("F18: falsificar X-Forwarded-For ya no renueva el contador")
    void aForgedForwardedForDoesNotBuyMoreAttempts() throws Exception {
        Store store = createStore("throttle-spoof");
        User admin = createAdmin(store, "throttle-spoof");

        // Una cabecera distinta en cada intento, que es todo lo que hacía falta para tener contador
        // nuevo: el filtro tomaba su primera entrada sin comprobar de quién venía. Sin proxies
        // confiables configurados —el valor por defecto— la cabecera se ignora y los cuatro intentos
        // caen en el mismo cubo, el del socket.
        for (int attempt = 1; attempt <= 3; attempt++) {
            mockMvc.perform(loginFrom(store, "198.51.100.77", "10.0.0." + attempt, admin.getEmail(), "mal"))
                    .andExpect(status().isUnauthorized());
        }

        mockMvc.perform(loginFrom(store, "198.51.100.77", "203.0.113.99", admin.getEmail(), "mal"))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    @DisplayName("F18: dos clientes desde el mismo socket siguen contando por separado si no hay proxy")
    void differentSocketsKeepSeparateCounters() throws Exception {
        Store store = createStore("throttle-sockets");
        User admin = createAdmin(store, "throttle-sockets");

        for (int attempt = 1; attempt <= 3; attempt++) {
            mockMvc.perform(loginFrom(store, "198.51.100.30", null, admin.getEmail(), "mal"))
                    .andExpect(status().isUnauthorized());
        }
        mockMvc.perform(loginFrom(store, "198.51.100.30", null, admin.getEmail(), "mal"))
                .andExpect(status().isTooManyRequests());

        // Otro cliente, otra dirección: el arreglo no puede meter a todo el mundo en un solo cubo, que
        // es justamente lo que temía el comentario original del filtro.
        mockMvc.perform(loginFrom(store, "198.51.100.31", null, admin.getEmail(), "mal"))
                .andExpect(status().isUnauthorized());
    }

    private org.springframework.test.web.servlet.RequestBuilder loginRequest(
            Store store, String clientIp, String email, String password) throws Exception {
        // F18: el contador se separa fijando la dirección del SOCKET, no una cabecera. Antes bastaba
        // X-Forwarded-For, y eso es precisamente el fallo: ahora se ignora salvo que venga de un proxy
        // confiable, y no hay ninguno configurado. Sin esto los tres casos compartirían cubo, porque
        // MockHttpServletRequest usa 127.0.0.1 para todos (DEFAULT_REMOTE_ADDR).
        return loginFrom(store, clientIp, null, email, password);
    }

    private org.springframework.test.web.servlet.RequestBuilder loginFrom(
            Store store, String socketAddress, String forwardedFor, String email, String password) throws Exception {
        String body = objectMapper.writeValueAsString(new HashMap<>() {{
            put("email", email);
            put("password", password);
        }});
        var request = post("/api/admin/auth/login")
                .header("Host", hostHeader(store))
                .contentType("application/json")
                .content(body)
                .with(req -> {
                    req.setRemoteAddr(socketAddress);
                    return req;
                });
        return forwardedFor == null ? request : request.header("X-Forwarded-For", forwardedFor);
    }
}
