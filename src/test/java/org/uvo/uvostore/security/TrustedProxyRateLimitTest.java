package org.uvo.uvostore.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.uvo.uvostore.entity.security.User;
import org.uvo.uvostore.entity.tenant.Store;
import org.uvo.uvostore.support.IntegrationTestSupport;

import java.util.HashMap;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * F18, con un proxy inverso declarado. Contexto propio porque la lista de proxies confiables está vacía
 * en el resto de la suite, que es el valor por defecto.
 *
 * <p>Lo que se acredita aquí es que el arreglo no rompe lo que el comentario original del filtro quería
 * proteger: detrás de un proxy, {@code getRemoteAddr()} es el proxy, y si se quedara ahí todos los
 * clientes compartirían un contador y el login se cortaría a los cinco intentos del conjunto.
 */
@SpringBootTest(properties = {
        "app.rate-limit.login=3",
        "app.rate-limit.window-seconds=60",
        "app.rate-limit.trusted-proxies=203.0.113.7"
})
class TrustedProxyRateLimitTest extends IntegrationTestSupport {

    @Test
    @DisplayName("Detrás de un proxy confiable, dos clientes distintos tienen contadores distintos")
    void twoClientsBehindTheSameProxyAreCountedApart() throws Exception {
        Store store = createStore("proxy-split");
        User admin = createAdmin(store, "proxy-split");

        for (int attempt = 1; attempt <= 3; attempt++) {
            mockMvc.perform(login(store, "203.0.113.7", "198.51.100.10", admin.getEmail()))
                    .andExpect(status().isUnauthorized());
        }
        mockMvc.perform(login(store, "203.0.113.7", "198.51.100.10", admin.getEmail()))
                .andExpect(status().isTooManyRequests());

        // El otro cliente llega por el mismo proxy y no ha gastado nada.
        mockMvc.perform(login(store, "203.0.113.7", "198.51.100.11", admin.getEmail()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("La entrada que el cliente se inventa a la izquierda no le sirve de nada")
    void aForgedLeftmostEntryIsIgnored() throws Exception {
        Store store = createStore("proxy-forged");
        User admin = createAdmin(store, "proxy-forged");

        // Lo que llega cuando el cliente manda su propia cabecera y nginx la conserva delante de la IP
        // real (proxy_add_x_forwarded_for, la receta estándar). La mentira va primero; la verdad, al
        // final. Tomar el extremo izquierdo —lo que hacía el filtro— le daba un contador nuevo por
        // intento incluso con el proxy bien configurado.
        for (int attempt = 1; attempt <= 3; attempt++) {
            mockMvc.perform(login(store, "203.0.113.7", "10.0.0." + attempt + ", 198.51.100.20", admin.getEmail()))
                    .andExpect(status().isUnauthorized());
        }

        mockMvc.perform(login(store, "203.0.113.7", "172.16.9.9, 198.51.100.20", admin.getEmail()))
                .andExpect(status().isTooManyRequests());
    }

    private org.springframework.test.web.servlet.RequestBuilder login(
            Store store, String socketAddress, String forwardedFor, String email) throws Exception {
        String body = objectMapper.writeValueAsString(new HashMap<>() {{
            put("email", email);
            put("password", "contraseña-incorrecta");
        }});
        return post("/api/admin/auth/login")
                .header("Host", hostHeader(store))
                .header("X-Forwarded-For", forwardedFor)
                .contentType("application/json")
                .content(body)
                .with(req -> {
                    req.setRemoteAddr(socketAddress);
                    return req;
                });
    }
}
