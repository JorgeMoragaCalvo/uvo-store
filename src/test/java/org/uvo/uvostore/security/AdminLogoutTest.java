package org.uvo.uvostore.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.uvo.uvostore.entity.security.User;
import org.uvo.uvostore.entity.tenant.Store;
import org.uvo.uvostore.support.IntegrationTestSupport;

import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * GAP-13. «Salir» invalida el token en el servidor. El panel lo guarda en localStorage, y antes cerrar sesión
 * solo lo borraba del navegador: una copia hecha antes seguía sirviendo hasta vencer.
 */
class AdminLogoutTest extends IntegrationTestSupport {

    @Value("${jwt.secret}")
    private String jwtSecret;

    @Test
    @DisplayName("Después del logout, el mismo token recibe 401; un login nuevo funciona")
    void logoutRevokesTheTokenAndANewLoginWorks() throws Exception {
        Store store = createStore("logout-revoca");
        User admin = createAdmin(store, "logout-revoca");
        String token = loginAdmin(store, admin);
        mockMvc.perform(adminRequest(store, token)).andExpect(status().isOk());

        mockMvc.perform(logout(store, token)).andExpect(status().isNoContent());

        mockMvc.perform(adminRequest(store, token)).andExpect(status().isUnauthorized());
        String fresh = loginAdmin(store, admin);
        mockMvc.perform(adminRequest(store, fresh)).andExpect(status().isOk());
    }

    @Test
    @DisplayName("Sin token, o con uno vencido, el logout responde 204 y no hace nada")
    void logoutWithoutAValidTokenIsANoOp() throws Exception {
        Store store = createStore("logout-sin-token");
        User admin = createAdmin(store, "logout-sin-token");
        String token = loginAdmin(store, admin);
        String expired = new JwtService(jwtSecret, -60_000L)
                .generateToken(admin.getId(), admin.getEmail(), "ADMIN", store.getId(), List.of("ROLE_ADMIN"),
                        admin.getTokenVersion());

        mockMvc.perform(post("/api/admin/auth/logout").header("Host", hostHeader(store)))
                .andExpect(status().isNoContent());
        mockMvc.perform(logout(store, expired)).andExpect(status().isNoContent());

        // La sesión vigente del mismo admin sigue viva: el token vencido no llegó a nombrar a nadie.
        mockMvc.perform(adminRequest(store, token)).andExpect(status().isOk());
    }

    @Test
    @DisplayName("El token de otra tienda, enviado a esta, no revoca a nadie")
    void aTokenOfAnotherStoreRevokesNothing() throws Exception {
        Store store = createStore("logout-propia");
        Store other = createStore("logout-ajena");
        User admin = createAdmin(store, "logout-propia");
        User foreign = createAdmin(other, "logout-ajena");
        String token = loginAdmin(store, admin);
        String foreignToken = loginAdmin(other, foreign);

        // JwtAuthenticationFilter descarta un token cuyo `sid` no es la tienda del Host, así que llega sin principal.
        mockMvc.perform(logout(store, foreignToken)).andExpect(status().isNoContent());

        mockMvc.perform(adminRequest(other, foreignToken)).andExpect(status().isOk());
        mockMvc.perform(adminRequest(store, token)).andExpect(status().isOk());
    }

    private org.springframework.test.web.servlet.RequestBuilder logout(Store store, String token) {
        return post("/api/admin/auth/logout")
                .header("Host", hostHeader(store))
                .header("Authorization", "Bearer " + token);
    }

    private org.springframework.test.web.servlet.RequestBuilder adminRequest(Store store, String token) {
        return get("/api/admin/products")
                .header("Host", hostHeader(store))
                .header("Authorization", "Bearer " + token);
    }
}
