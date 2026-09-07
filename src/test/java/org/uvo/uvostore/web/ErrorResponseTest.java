package org.uvo.uvostore.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.uvo.uvostore.entity.customer.Customer;
import org.uvo.uvostore.entity.security.User;
import org.uvo.uvostore.entity.tenant.Store;
import org.uvo.uvostore.support.IntegrationTestSupport;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * M1 y M8. GlobalExceptionHandler mapeaba IllegalStateException a 400 devolviendo su mensaje, y ese
 * tipo lo lanzaban por igual las reglas de negocio ("Este código ya está en uso") y los fallos
 * internos que envuelven una causa ("Error al crear sesión de pago: " + e.getMessage()). Costaba dos
 * veces: el texto crudo de Stripe llegaba al cliente, y el fallo nunca llegaba a Sentry, porque solo
 * el handler genérico captura.
 *
 * <p>Del otro lado, un cuerpo JSON mal formado, un parámetro con el tipo equivocado o un campo de
 * ordenación inexistente caían al handler genérico y salían como 500 — errores del cliente contados
 * como caídas del servidor.
 */
class ErrorResponseTest extends IntegrationTestSupport {

    // --- M1: el cliente se equivocó, no el servidor ------------------------------------------------

    @Test
    @DisplayName("Un cuerpo JSON mal formado es 400, no 500")
    void aMalformedBodyIsABadRequest() throws Exception {
        Store store = createStore("err-json");

        mockMvc.perform(post("/api/customer/auth/login")
                        .header("Host", hostHeader(store))
                        .contentType("application/json")
                        .content("{"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Un parámetro con el tipo equivocado es 400, no 500")
    void aBadlyTypedParameterIsABadRequest() throws Exception {
        Store store = createStore("err-type");

        // min_price es un BigDecimal: "barato" no convierte.
        mockMvc.perform(get("/api/v1/products?min_price=barato")
                        .header("Host", hostHeader(store)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("El 400 por petición mal formada no filtra internos de Jackson ni de la entidad")
    void theMalformedRequestMessageSaysNothingAboutTheStack() throws Exception {
        Store store = createStore("err-leak");

        mockMvc.perform(post("/api/customer/auth/login")
                        .header("Host", hostHeader(store))
                        .contentType("application/json")
                        .content("{"))
                .andExpect(jsonPath("$.message").value("La solicitud no es válida. Revisa los datos enviados."));
    }

    // --- M1: la regla de negocio sigue hablando ----------------------------------------------------

    @Test
    @DisplayName("Un error de negocio sigue siendo 400 y conserva su mensaje")
    void aBusinessRuleStillExplainsItself() throws Exception {
        Store store = createStore("err-biz");
        Customer existing = createCustomer(store, "ya-registrado");

        mockMvc.perform(post("/api/customer/auth/register")
                        .header("Host", hostHeader(store))
                        .contentType("application/json")
                        .content("""
                                {"email":"%s","password":"contrasena-larga","firstName":"Ana","lastName":"Pérez"}
                                """.formatted(existing.getEmail())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", org.hamcrest.Matchers.containsString("ya está registrado")));
    }

    // --- M9: el mínimo de contraseña ---------------------------------------------------------------

    @Test
    @DisplayName("No se puede registrar una cuenta con una contraseña de tres caracteres")
    void aThreeCharacterPasswordIsRefused() throws Exception {
        Store store = createStore("err-pass");

        mockMvc.perform(post("/api/customer/auth/register")
                        .header("Host", hostHeader(store))
                        .contentType("application/json")
                        .content("""
                                {"email":"nueva-%d@test.local","password":"abc","firstName":"Ana","lastName":"Pérez"}
                                """.formatted(nextSeq())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.password").exists());
    }

    // --- M8: la allowlist de ordenación ------------------------------------------------------------

    @Test
    @DisplayName("Un campo de ordenación inexistente no rompe el listado: cae al orden por defecto")
    void anUnknownSortFieldFallsBackInsteadOfBlowingUp() throws Exception {
        Store store = createStore("sort-bad");
        User admin = createAdmin(store, "sort-bad");
        String token = loginAdmin(store, admin);

        for (String path : new String[]{"orders", "customers", "products", "users"}) {
            mockMvc.perform(get("/api/admin/" + path + "?sortField=noExiste")
                            .header("Host", hostHeader(store))
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk());
        }
    }

    @Test
    @DisplayName("Un campo permitido se respeta")
    void anAllowedSortFieldIsHonoured() throws Exception {
        Store store = createStore("sort-ok");
        User admin = createAdmin(store, "sort-ok");
        String token = loginAdmin(store, admin);

        mockMvc.perform(get("/api/admin/orders?sortField=total&sortDirection=asc")
                        .header("Host", hostHeader(store))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Una columna que no es del listado se ignora, no ordena por ella")
    void aColumnThatIsNotOnTheListingDoesNotOrderIt() throws Exception {
        Store store = createStore("sort-off");
        User first = createAdmin(store, "sort-off-a");
        User second = createAdmin(store, "sort-off-b");
        String token = loginAdmin(store, first);

        // `phone` es una propiedad real de User, así que sin allowlist Sort.by(...) la aceptaría y
        // ordenaría de verdad por ella. Los teléfonos van al revés que createdAt a propósito: pedido
        // ASC, ordenar por phone devolvería primero al segundo usuario y ordenar por createdAt —el
        // campo por defecto al que debe caer— devuelve primero al primero. La dirección no la filtra
        // la allowlist, solo el campo.
        first.setPhone("2-segundo");
        second.setPhone("1-primero");
        userRepository.save(first);
        userRepository.save(second);

        mockMvc.perform(get("/api/admin/users?sortField=phone&sortDirection=asc")
                        .header("Host", hostHeader(store))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].email").value(first.getEmail()));
    }
}
