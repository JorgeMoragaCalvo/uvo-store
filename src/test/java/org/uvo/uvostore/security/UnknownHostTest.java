package org.uvo.uvostore.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.uvo.uvostore.support.IntegrationTestSupport;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * IMP-05. Un Host que no es de ninguna tienda no llega a las rutas de tienda.
 *
 * <p>nginx corta los hosts que no tiene configurados ({@code ops/nginx/default.conf}), pero el comodín de la
 * plataforma deja pasar cualquier subdominio, exista la tienda o no. Antes de este cambio esas peticiones
 * llegaban al controlador: unas fallaban con el 400 de {@code TenantContext.requireCurrent()} y otras
 * dependían de que la ruta lo llamara. Ahora es un 404 en el filtro, antes de autenticar nada.
 */
class UnknownHostTest extends IntegrationTestSupport {

    private static String unknownSubdomain() {
        // Del dominio de plataforma de las pruebas (localhost): pasa el filtro de forma del resolver y se
        // busca por slug, que no existe.
        return "no-existe-" + nextSeq() + ".localhost";
    }

    @ParameterizedTest
    // /api/customer/** también está en la lista, pero con las cuentas de comprador apagadas (el default) ahí
    // responde antes el 404 de DeferredSurfaceGateFilter, así que no distingue nada.
    @ValueSource(strings = {"/api/v1/products", "/api/v1/store-settings", "/api/admin/products"})
    @DisplayName("Un subdominio inexistente del comodín recibe 404 en la vitrina y en el panel")
    void anUnknownSubdomainGetsNotFoundOnStoreRoutes(String path) throws Exception {
        mockMvc.perform(get(path).header("Host", unknownSubdomain()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Tienda no encontrada"));
    }

    @Test
    @DisplayName("Un dominio ajeno también, y antes de autenticar: el login no llega a buscar el correo")
    void aForeignDomainGetsNotFoundBeforeAuthentication() throws Exception {
        mockMvc.perform(post("/api/admin/auth/login")
                        .header("Host", "tienda-que-nadie-registro.example")
                        .contentType("application/json")
                        .content("{\"email\":\"a@test.local\",\"password\":\"x\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Tienda no encontrada"));
    }

    @Test
    @DisplayName("Lo que no tiene tienda por Host sigue respondiendo: salud, comunas y la API de plataforma")
    void routesWithoutAStoreAreUnaffected() throws Exception {
        String host = unknownSubdomain();

        mockMvc.perform(get("/actuator/health").header("Host", host))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/geo/regions").header("Host", host))
                .andExpect(status().isOk());
        // Sin clave de plataforma: el 401 de PlatformApiKeyAuthFilter, no el 404 de este filtro.
        mockMvc.perform(get("/api/platform/stores/1").header("Host", host))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Una tienda que existe sigue resolviendo por su subdominio")
    void aKnownStoreStillResolves() throws Exception {
        mockMvc.perform(get("/api/v1/products").header("Host", hostHeader(createStore("host-ok"))))
                .andExpect(status().isOk());
    }
}
