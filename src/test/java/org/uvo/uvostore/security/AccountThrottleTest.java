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
 * F18, segunda mitad: el techo por cuenta.
 *
 * <p>Contexto propio con un presupuesto pequeño. El límite por IP se deja holgado a propósito en todos
 * los casos —cada intento llega desde una dirección distinta— porque lo que hay que acreditar es
 * exactamente lo que el límite por IP <b>no</b> ve: un atacante que reparte los intentos entre muchas
 * direcciones y los concentra en una sola cuenta.
 */
@SpringBootTest(properties = {
        "app.rate-limit.login=100000",
        "app.rate-limit.forgot-password=100000",
        "app.rate-limit.account-attempts=4",
        "app.rate-limit.account-window-seconds=900"
})
class AccountThrottleTest extends IntegrationTestSupport {

    @Test
    @DisplayName("Cuatro fallos desde cuatro direcciones distintas agotan el presupuesto de la cuenta")
    void failuresFromManyAddressesStillExhaustTheAccountBudget() throws Exception {
        Store store = createStore("acct-brute");
        User admin = createAdmin(store, "acct-brute");

        for (int attempt = 1; attempt <= 4; attempt++) {
            mockMvc.perform(login(store, "198.51.100." + attempt, admin.getEmail(), "mal"))
                    .andExpect(status().isUnauthorized());
        }

        mockMvc.perform(login(store, "198.51.100.200", admin.getEmail(), "mal"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.status").value(429));

        // Y tampoco entra con la contraseña correcta mientras dure la ventana: si el presupuesto solo
        // frenara los fallos, el atacante seguiría pudiendo confirmar un acierto.
        mockMvc.perform(login(store, "198.51.100.201", admin.getEmail(), TEST_PASSWORD))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    @DisplayName("Entrar borra el contador: los errores previos no se arrastran")
    void aSuccessfulLoginClearsTheCounter() throws Exception {
        Store store = createStore("acct-clear");
        User admin = createAdmin(store, "acct-clear");

        for (int attempt = 1; attempt <= 3; attempt++) {
            mockMvc.perform(login(store, "198.51.100." + attempt, admin.getEmail(), "mal"))
                    .andExpect(status().isUnauthorized());
        }
        mockMvc.perform(login(store, "198.51.100.9", admin.getEmail(), TEST_PASSWORD))
                .andExpect(status().isOk());

        // Con el contador sin borrar, el cuarto fallo sería el que agota y este quinto daría 429.
        for (int attempt = 1; attempt <= 4; attempt++) {
            mockMvc.perform(login(store, "198.51.100.1" + attempt, admin.getEmail(), "mal"))
                    .andExpect(status().isUnauthorized());
        }
    }

    @Test
    @DisplayName("Dos cuentas no comparten presupuesto, ni el mismo correo en dos tiendas")
    void budgetsAreSeparatePerAccountAndPerStore() throws Exception {
        Store storeA = createStore("acct-iso-a");
        Store storeB = createStore("acct-iso-b");
        User adminA = createAdmin(storeA, "acct-iso-a");
        User otherA = createAdmin(storeA, "acct-iso-other");

        for (int attempt = 1; attempt <= 4; attempt++) {
            mockMvc.perform(login(storeA, "198.51.100." + attempt, adminA.getEmail(), "mal"))
                    .andExpect(status().isUnauthorized());
        }
        mockMvc.perform(login(storeA, "198.51.100.50", adminA.getEmail(), "mal"))
                .andExpect(status().isTooManyRequests());

        // Otra cuenta de la misma tienda: intacta.
        mockMvc.perform(login(storeA, "198.51.100.51", otherA.getEmail(), "mal"))
                .andExpect(status().isUnauthorized());

        // Y el MISMO correo en otra tienda: intacto también. Los correos son por tienda
        // (findByStoreIdAndEmail), así que sin el storeId en la clave una tienda podría agotarle el
        // presupuesto a la otra.
        mockMvc.perform(login(storeB, "198.51.100.52", adminA.getEmail(), "mal"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("En olvidé-contraseña cuenta cada llamada, y un correo inexistente también acaba en 429")
    void forgotPasswordCountsEveryCallEvenForUnknownEmails() throws Exception {
        Store store = createStore("acct-forgot");
        createAdmin(store, "acct-forgot");

        // Un correo que no existe en esta tienda. Responde 200 a propósito para no revelar qué correos
        // hay, y por eso mismo el 429 tiene que llegar igual: si solo contara los correos reales, el
        // propio límite diría cuáles son.
        String unknown = "nadie-" + nextSeq() + "@test.local";
        for (int attempt = 1; attempt <= 4; attempt++) {
            mockMvc.perform(forgotPassword(store, "198.51.100." + attempt, unknown))
                    .andExpect(status().isOk());
        }

        mockMvc.perform(forgotPassword(store, "198.51.100.60", unknown))
                .andExpect(status().isTooManyRequests());
    }

    private org.springframework.test.web.servlet.RequestBuilder login(
            Store store, String socketAddress, String email, String password) throws Exception {
        String body = objectMapper.writeValueAsString(new HashMap<>() {{
            put("email", email);
            put("password", password);
        }});
        return post("/api/admin/auth/login")
                .header("Host", hostHeader(store))
                .contentType("application/json")
                .content(body)
                .with(req -> {
                    req.setRemoteAddr(socketAddress);
                    return req;
                });
    }

    private org.springframework.test.web.servlet.RequestBuilder forgotPassword(
            Store store, String socketAddress, String email) throws Exception {
        String body = objectMapper.writeValueAsString(new HashMap<>() {{
            put("email", email);
        }});
        return post("/api/admin/auth/forgot-password")
                .header("Host", hostHeader(store))
                .contentType("application/json")
                .content(body)
                .with(req -> {
                    req.setRemoteAddr(socketAddress);
                    return req;
                });
    }
}
