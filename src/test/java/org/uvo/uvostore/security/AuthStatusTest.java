package org.uvo.uvostore.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.uvo.uvostore.entity.customer.Customer;
import org.uvo.uvostore.entity.security.User;
import org.uvo.uvostore.entity.tenant.Store;
import org.uvo.uvostore.support.IntegrationTestSupport;

import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * F19. Qué código responde cada forma de "no puedes".
 *
 * <p>Los tres casos van juntos en una clase porque el valor está en el contraste: antes los tres
 * respondían 403, y el panel —que solo cierra sesión ante 401— no podía distinguir «tu sesión se acabó»
 * de «esto no te toca». Con el primero quedaba atrapado mostrando errores sin volver al login; si en vez
 * de arreglar el código se hubiera hecho que la SPA también cerrara sesión ante 403, el segundo y el
 * tercero habrían echado al login a quien simplemente abrió una sección ajena.
 */
class AuthStatusTest extends IntegrationTestSupport {

    @Value("${jwt.secret}")
    private String jwtSecret;

    @Test
    @DisplayName("Sin credencial: 401, con WWW-Authenticate y el cuerpo del proyecto")
    void anAnonymousRequestIsUnauthorized() throws Exception {
        Store store = createStore("auth-anon");

        mockMvc.perform(get("/api/admin/products").header("Host", hostHeader(store)))
                .andExpect(status().isUnauthorized())
                // Lo que exige el estándar para un 401 y antes no se mandaba.
                .andExpect(header().string("WWW-Authenticate", "Bearer"))
                // Y el cuerpo con forma de ApiError: el 403 por defecto salía por sendError, despachaba
                // a /error y devolvía el JSON de Boot, sin `message`. El interceptor del panel lee ese
                // campo, así que lo que se veía en pantalla era el texto crudo de axios.
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.error").value("Unauthorized"))
                .andExpect(jsonPath("$.message").value("No autenticado. Inicia sesión para continuar."));
    }

    @Test
    @DisplayName("Un token que no se puede verificar: 401")
    void anUnparseableTokenIsUnauthorized() throws Exception {
        Store store = createStore("auth-garbage");

        mockMvc.perform(get("/api/admin/products")
                        .header("Host", hostHeader(store))
                        .header("Authorization", "Bearer esto-no-es-un-jwt"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Un token caducado: 401, que es el caso que atrapaba al panel")
    void anExpiredTokenIsUnauthorized() throws Exception {
        Store store = createStore("auth-expired");
        User admin = createAdmin(store, "auth-expired");

        // Firmado con la MISMA clave que la aplicación, solo con la expiración en el pasado: así se
        // ejerce el catch de JwtException del filtro y no otra rama. Un token inventado probaría una
        // cosa distinta (firma inválida), y el caso real de este hallazgo es el que caduca a las 24 h.
        String expired = new JwtService(jwtSecret, -60_000L)
                .generateToken(admin.getId(), admin.getEmail(), "ADMIN", store.getId(), List.of("ROLE_ADMIN"), 0);

        mockMvc.perform(get("/api/admin/products")
                        .header("Host", hostHeader(store))
                        .header("Authorization", "Bearer " + expired))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    @DisplayName("Un token de cliente contra una ruta de admin: 403, no 401")
    void aCustomerTokenOnAnAdminRouteIsForbidden() throws Exception {
        Store store = createStore("auth-wrong-role");
        Customer customer = createCustomer(store, "auth-wrong-role");
        String token = loginCustomer(store, customer);

        // Este es el caso que separa el arreglo de "cambiar 403 por 401 en todas partes": hay un
        // autenticado de verdad, lo que le falta es la autoridad. Cerrar su sesión sería lo incorrecto.
        mockMvc.perform(get("/api/admin/products")
                        .header("Host", hostHeader(store))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist("WWW-Authenticate"))
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.message").value("No tienes permiso para esta acción."));
    }

    @Test
    @DisplayName("Un administrador autenticado sin el permiso sigue recibiendo 403")
    void aRestrictedAdminStillGetsForbidden() throws Exception {
        Store store = createStore("auth-no-perm");
        User restricted = createAdminWithPermissions(store, "auth-no-perm", "products.view");
        String token = loginAdmin(store, restricted);

        // Este 403 no sale de la cadena de seguridad sino de @PreAuthorize, a través de
        // GlobalExceptionHandler, y ya estaba bien. Se comprueba aquí para que quede junto a los otros
        // dos: si alguna vez se "arregla" convirtiendo todos los 403 en 401, este caso lo dirá.
        mockMvc.perform(get("/api/admin/payment-gateways")
                        .header("Host", hostHeader(store))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));
    }
}
