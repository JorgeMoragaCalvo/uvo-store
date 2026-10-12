package org.uvo.uvostore.customer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.uvo.uvostore.entity.catalog.Product;
import org.uvo.uvostore.entity.customer.Customer;
import org.uvo.uvostore.entity.customer.enums.AccountStatus;
import org.uvo.uvostore.entity.tenant.Store;
import org.uvo.uvostore.support.IntegrationTestSupport;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * PROD-07. Lo que se promete al diferir las cuentas de comprador, comprobado.
 *
 * <p><b>Esta clase NO lleva {@code @WithCustomerAccounts} a propósito.</b> Corre con el valor que se
 * despacha —{@code app.customer-accounts.enabled=false}— mientras las seis clases que sí ejercitan las
 * cuentas lo encienden en el suyo. Así el criterio de PROD-07 («deshabilitar de forma comprobable»)
 * depende de un test y no de un comentario: si alguien quitara el filtro, esto cae.
 *
 * <p>Lo que se afirma son las dos mitades de la decisión: que la API del comprador no existe desde
 * fuera, y que <b>la compra como invitado no se toca</b> — incluido que la fila quede en un estado del
 * que se la pueda invitar el día que se enciendan, que es la trampa que F24 cerró una vez ya.
 */
class CustomerAccountsDisabledTest extends IntegrationTestSupport {

    @Test
    @DisplayName("Los tres endpoints de sesión del comprador responden 404")
    void theCustomerAuthEndpointsAreGone() throws Exception {
        Store store = createStore("acc-off-auth");

        expectGone(post("/api/customer/auth/login")
                .header("Host", hostHeader(store))
                .contentType("application/json")
                .content("{\"email\":\"alguien@test.local\",\"password\":\"password123\"}"));

        expectGone(post("/api/customer/auth/register")
                .header("Host", hostHeader(store))
                .contentType("application/json")
                .content("""
                        {"email":"nuevo@test.local","password":"password123","firstName":"Nuevo","lastName":"Cliente"}
                        """));

        expectGone(post("/api/customer/auth/accept-invitation")
                .header("Host", hostHeader(store))
                .contentType("application/json")
                .content("{\"token\":\"da-igual\",\"password\":\"password123\"}"));
    }

    @Test
    @DisplayName("El 404 llega sin autenticación, y no es el 401 de «vuelve a entrar»")
    void theGateAnswersBeforeSecurity() throws Exception {
        Store store = createStore("acc-off-anon");

        // La misma ruta protegida, sin credencial. Si el filtro corriera DESPUÉS de la cadena de
        // seguridad, esto sería el 401 con WWW-Authenticate de ApiSecurityErrorHandlers (F19) — que le
        // diría a un cliente que se identifique para algo que no existe. El contraste con
        // AuthStatusTest.anAnonymousRequestIsUnauthorized, que sí espera ese 401 en /api/admin/**, es
        // lo que prueba que el orden de los filtros es el declarado.
        mockMvc.perform(get("/api/customer/account").header("Host", hostHeader(store)))
                .andExpect(status().isNotFound())
                .andExpect(header().doesNotExist("WWW-Authenticate"))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.error").value("Not Found"));
    }

    @Test
    @DisplayName("Cuenta y direcciones responden 404 incluso con un token de cliente válido")
    void theCustomerSurfaceIsGoneEvenWithACredential() throws Exception {
        Store store = createStore("acc-off-token");
        Customer customer = createCustomer(store, "acc-off-token");
        // Acuñado, no obtenido del login: ese endpoint ya no responde. Es la única forma de distinguir
        // "está apagado" de "no te autenticaste", y el caso que de verdad importa — un comprador con una
        // sesión viva de antes del interruptor tampoco entra.
        String token = customerToken(store, customer);

        expectGone(get("/api/customer/account")
                .header("Host", hostHeader(store))
                .header("Authorization", "Bearer " + token));

        expectGone(get("/api/customer/addresses")
                .header("Host", hostHeader(store))
                .header("Authorization", "Bearer " + token));
    }

    @Test
    @DisplayName("La compra como invitado sigue funcionando y no deja la fila atrapada")
    void guestCheckoutStillWorksAndLeavesAnInvitableRow() throws Exception {
        Store store = createStore("acc-off-checkout");
        // Una tienda nueva no cubre ninguna zona de envío, así que el checkout respondería 409 (A7). Lo
        // que se mide aquí es la cuenta, no el envío.
        disableShipping(store);
        Product product = createProduct(store, createCategory(store, "Cat"), "Producto", BigDecimal.valueOf(10000));
        String email = "acc-off-guest-" + nextSeq() + "@test.local";

        mockMvc.perform(post("/api/v1/checkout")
                        .header("Host", hostHeader(store))
                        .contentType("application/json")
                        .content("""
                                {
                                  "customer": {"email":"%s","firstName":"Invitado","lastName":"Comprador","phone":"+56911111111"},
                                  "shippingAddress": {"addressLine1":"Calle 1","city":"Santiago","state":"RM","postalCode":"8320000","country":"CL"},
                                  "region": "RM", "commune": "Santiago",
                                  "items": [{"id":%d,"type":"product","quantity":1}],
                                  "paymentMethod": "manual"
                                }
                                """.formatted(email, product.getId())))
                .andExpect(status().isOk());

        Customer guest = customerRepository.findByStoreIdAndEmail(store.getId(), email).orElseThrow();
        // GUEST y sin token es lo que mantiene posible encender las cuentas después: una fila INVITED con
        // un token que nunca se envió quedaría atrapada para siempre —markInvitedIfGuest no la vuelve a
        // invitar porque no es GUEST, y el registro la rechaza porque el correo existe—. Es el fallo de
        // F24 con otra forma, y la razón por la que el interruptor también apaga el checkout.
        assertThat(guest.getAccountStatus()).isEqualTo(AccountStatus.GUEST);
        assertThat(guest.getInvitationToken()).isNull();
        assertThat(guest.getInvitationSentAt()).isNull();
    }

    private void expectGone(MockHttpServletRequestBuilder request) throws Exception {
        mockMvc.perform(request)
                .andExpect(status().isNotFound())
                // El mismo cuerpo que cualquier otro error del proyecto, serializado con ApiError y no a
                // mano: un cliente lo parsea igual que el resto.
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.error").value("Not Found"))
                .andExpect(jsonPath("$.message").value("El recurso solicitado no existe."))
                .andExpect(jsonPath("$.timestamp").exists());
    }
}
