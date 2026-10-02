package org.uvo.uvostore.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.uvo.uvostore.entity.customer.Customer;
import org.uvo.uvostore.entity.tenant.Store;
import org.uvo.uvostore.support.IntegrationTestSupport;

import java.util.HashMap;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * F22. La contraseña nueva de un cliente invalida sus tokens anteriores.
 *
 * <p>El mecanismo de revocación existe desde A5 —{@code JwtAuthenticationFilter} compara el {@code tv}
 * del token contra la versión actual en cada petición— pero <b>ningún flujo de cliente lo movía</b>: los
 * tres sitios que lo usaban eran de administración. Así que un token de cliente robado sobrevivía a lo
 * único que la víctima puede hacer para detenerlo, hasta caducar por su cuenta a las 24 h.
 *
 * <p>Esta clase es además la primera cobertura de {@code /api/customer/account}, que no tenía ninguna
 * —solo lo rozaba {@code MultiTenancyIsolationTest} para el aislamiento entre tiendas—, y la primera de
 * la mitad cliente de A5: los tests de revocación de {@code SecurityHardeningTest} son todos de admin.
 */
class CustomerTokenRevocationTest extends IntegrationTestSupport {

    private static final String NEW_PASSWORD = "contrasena-nueva-123";

    @Test
    @DisplayName("Tras cambiar la contraseña, el token anterior deja de servir")
    void changingThePasswordRevokesTheOldToken() throws Exception {
        Store store = createStore("cust-revoke");
        Customer customer = createCustomer(store, "cust-revoke");
        String oldToken = loginCustomer(store, customer);

        // Antes del cambio el token entra, para que el caso no pueda pasar por accidente.
        mockMvc.perform(profile(store, oldToken)).andExpect(status().isOk());

        mockMvc.perform(changePassword(store, oldToken, TEST_PASSWORD, NEW_PASSWORD))
                .andExpect(status().isNoContent());

        // Esto respondía 200. Es el fallo entero: el atacante con el token robado seguía dentro después
        // de que el dueño cambiara la contraseña.
        mockMvc.perform(profile(store, oldToken)).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Y con la contraseña nueva se entra otra vez")
    void theNewPasswordWorks() throws Exception {
        Store store = createStore("cust-newpass");
        Customer customer = createCustomer(store, "cust-newpass");
        String oldToken = loginCustomer(store, customer);

        mockMvc.perform(changePassword(store, oldToken, TEST_PASSWORD, NEW_PASSWORD))
                .andExpect(status().isNoContent());

        // La otra mitad de lo que pide el hallazgo: revocar no puede significar romperlo todo. El token
        // recién emitido lleva la versión ya incrementada.
        String freshToken = loginWithPassword(store, customer.getEmail(), NEW_PASSWORD);
        mockMvc.perform(profile(store, freshToken)).andExpect(status().isOk());
    }

    @Test
    @DisplayName("Si la contraseña actual es incorrecta, la sesión no se toca")
    void aWrongCurrentPasswordRevokesNothing() throws Exception {
        Store store = createStore("cust-wrongpass");
        Customer customer = createCustomer(store, "cust-wrongpass");
        String token = loginCustomer(store, customer);

        mockMvc.perform(changePassword(store, token, "no-es-la-actual", NEW_PASSWORD))
                .andExpect(status().isUnauthorized());

        // Importa el orden: si se revocara antes de validar la contraseña actual, cualquiera con el token
        // podría echar al dueño de su propia sesión probando contraseñas al azar.
        mockMvc.perform(profile(store, token)).andExpect(status().isOk());
    }

    @Test
    @DisplayName("Un cliente borrado deja de autenticarse de inmediato, sin esperar el TTL")
    void deletingACustomerRevokesItsTokenImmediately() throws Exception {
        Store store = createStore("cust-deleted");
        Customer customer = createCustomer(store, "cust-deleted");
        String token = loginCustomer(store, customer);

        // La petición previa deja la versión en la caché de 60 s: sin invalidarla, el token de un cliente
        // ya borrado seguiría entrando hasta que la entrada expirase. UserServiceImpl.deleteUser ya lo
        // tenía resuelto para los admins; esta mitad no.
        mockMvc.perform(profile(store, token)).andExpect(status().isOk());

        String adminToken = loginAdmin(store, createAdmin(store, "cust-deleted-admin"));
        mockMvc.perform(delete("/api/admin/customers/" + customer.getId())
                        .header("Host", hostHeader(store))
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNoContent());

        mockMvc.perform(profile(store, token)).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Revocar a un cliente no echa a los demás")
    void revokingOneCustomerLeavesTheOthersAlone() throws Exception {
        Store store = createStore("cust-others");
        Customer one = createCustomer(store, "cust-others-a");
        Customer two = createCustomer(store, "cust-others-b");
        String tokenOne = loginCustomer(store, one);
        String tokenTwo = loginCustomer(store, two);

        mockMvc.perform(changePassword(store, tokenOne, TEST_PASSWORD, NEW_PASSWORD))
                .andExpect(status().isNoContent());

        mockMvc.perform(profile(store, tokenOne)).andExpect(status().isUnauthorized());
        mockMvc.perform(profile(store, tokenTwo)).andExpect(status().isOk());
    }

    // --- fixtures ----------------------------------------------------------------------------------

    private org.springframework.test.web.servlet.RequestBuilder profile(Store store, String token) {
        return get("/api/customer/account")
                .header("Host", hostHeader(store))
                .header("Authorization", "Bearer " + token);
    }

    private org.springframework.test.web.servlet.RequestBuilder changePassword(
            Store store, String token, String current, String next) throws Exception {
        String body = objectMapper.writeValueAsString(new HashMap<String, String>() {{
            put("currentPassword", current);
            put("newPassword", next);
        }});
        return put("/api/customer/account/password")
                .header("Host", hostHeader(store))
                .header("Authorization", "Bearer " + token)
                .contentType("application/json")
                .content(body);
    }

    /** Como loginCustomer, pero con una contraseña distinta de TEST_PASSWORD. */
    private String loginWithPassword(Store store, String email, String password) throws Exception {
        String body = objectMapper.writeValueAsString(new HashMap<String, String>() {{
            put("email", email);
            put("password", password);
        }});
        String response = mockMvc.perform(post("/api/customer/auth/login")
                        .header("Host", hostHeader(store))
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).get("token").asText();
    }
}
