package org.uvo.uvostore.auth;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.uvo.uvostore.entity.catalog.Product;
import org.uvo.uvostore.entity.customer.Customer;
import org.uvo.uvostore.entity.customer.enums.AccountStatus;
import org.uvo.uvostore.entity.tenant.Store;
import org.uvo.uvostore.repository.CustomerRepository;
import org.uvo.uvostore.support.IntegrationTestSupport;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * F24. El recorrido que el hallazgo decía imposible: comprar como invitado, darse de alta y entrar.
 *
 * <p>El checkout ya generaba token, fecha y estado {@code INVITED}, pero no salía correo ni había endpoint
 * de aceptación, así que ese correo quedaba <b>inservible</b> en la tienda: el registro lo rechazaba por
 * existir y el login exige {@code ACTIVE} con contraseña.
 *
 * <p><b>El token se lee de la base, no de un buzón.</b> En los tests no hay SMTP y
 * {@code EmailServiceImpl} registra y omite el envío — es exactamente como trabaja
 * {@code PasswordResetTest}, y equivale a leerlo del enlace del correo en producción.
 */
class CustomerInvitationTest extends IntegrationTestSupport {

    @Autowired
    private CustomerRepository customerRepository;
    @PersistenceContext
    private EntityManager entityManager;

    private static final String NEW_PASSWORD = "contrasena-de-invitado";

    @Test
    @DisplayName("Comprar como invitado deja la cuenta INVITED con un token que se puede canjear")
    void aGuestCheckoutLeavesAnInvitation() throws Exception {
        Store store = createStore("inv-guest");
        Customer guest = checkoutAsGuest(store, "invitado");

        assertThat(guest.getAccountStatus()).isEqualTo(AccountStatus.INVITED);
        assertThat(guest.getInvitationToken()).isNotBlank();
        assertThat(guest.getInvitationSentAt()).isNotNull();
        // Sin contraseña: es lo que hace que hoy no pueda entrar por ningún sitio.
        assertThat(guest.getPassword()).isNull();
    }

    @Test
    @DisplayName("Aceptar la invitación activa la cuenta y devuelve la sesión iniciada")
    void acceptingTheInvitationActivatesTheAccount() throws Exception {
        Store store = createStore("inv-accept");
        Customer guest = checkoutAsGuest(store, "acepta");

        String sessionToken = objectMapper.readTree(
                        mockMvc.perform(accept(store, guest.getInvitationToken(), NEW_PASSWORD))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.email").value(guest.getEmail()))
                                .andExpect(jsonPath("$.type").value("CUSTOMER"))
                                .andReturn().getResponse().getContentAsString())
                .get("token").asText();

        // La sesión llega ya iniciada, así que el invitado no tiene que hacer un segundo viaje.
        mockMvc.perform(get("/api/customer/account")
                        .header("Host", hostHeader(store))
                        .header("Authorization", "Bearer " + sessionToken))
                .andExpect(status().isOk());

        Customer activated = customerRepository.findById(guest.getId()).orElseThrow();
        assertThat(activated.getAccountStatus()).isEqualTo(AccountStatus.ACTIVE);
        assertThat(activated.getInvitationToken()).as("de un solo uso: el token se consume").isNull();
    }

    @Test
    @DisplayName("Y después el login normal funciona con la contraseña nueva")
    void theNormalLoginWorksAfterwards() throws Exception {
        Store store = createStore("inv-login");
        Customer guest = checkoutAsGuest(store, "entra");

        mockMvc.perform(accept(store, guest.getInvitationToken(), NEW_PASSWORD))
                .andExpect(status().isOk());

        // El final del recorrido que el hallazgo decía inalcanzable.
        mockMvc.perform(post("/api/customer/auth/login")
                        .header("Host", hostHeader(store))
                        .contentType("application/json")
                        .content("""
                                {"email":"%s","password":"%s"}""".formatted(guest.getEmail(), NEW_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty());
    }

    @Test
    @DisplayName("El mismo token no sirve dos veces")
    void theTokenIsSingleUse() throws Exception {
        Store store = createStore("inv-once");
        Customer guest = checkoutAsGuest(store, "unavez");
        String token = guest.getInvitationToken();

        mockMvc.perform(accept(store, token, NEW_PASSWORD)).andExpect(status().isOk());

        mockMvc.perform(accept(store, token, "otra-contrasena-valida"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("La invitación no es válida o ha expirado"));
    }

    @Test
    @DisplayName("Una invitación caducada se rechaza")
    void anExpiredInvitationIsRejected() throws Exception {
        Store store = createStore("inv-expired");
        Customer guest = checkoutAsGuest(store, "caducada");

        // El TTL por defecto son 30 días; se retrasa la fecha de envío con SQL nativo porque la pone el
        // propio checkout (mismo recurso que ReportTestSupport usó en F20 con created_at).
        entityManager.createNativeQuery("UPDATE customers SET invitation_sent_at = :sentAt WHERE id = :id")
                .setParameter("sentAt", Instant.now().minus(31, ChronoUnit.DAYS))
                .setParameter("id", guest.getId())
                .executeUpdate();
        entityManager.clear();

        mockMvc.perform(accept(store, guest.getInvitationToken(), NEW_PASSWORD))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("La invitación no es válida o ha expirado"));
    }

    @Test
    @DisplayName("Un token inventado se rechaza con el mismo mensaje que uno caducado")
    void anUnknownTokenIsRejectedIndistinguishably() throws Exception {
        Store store = createStore("inv-unknown");

        // No se le dice a quien prueba tokens en qué se equivocó.
        mockMvc.perform(accept(store, "token-que-no-existe", NEW_PASSWORD))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("La invitación no es válida o ha expirado"));
    }

    @Test
    @DisplayName("Una invitación no se puede canjear desde el dominio de otra tienda")
    void anInvitationCannotBeRedeemedFromAnotherStore() throws Exception {
        Store store = createStore("inv-tenant-a");
        Store other = createStore("inv-tenant-b");
        Customer guest = checkoutAsGuest(store, "ajena");

        mockMvc.perform(accept(other, guest.getInvitationToken(), NEW_PASSWORD))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Un segundo pedido del mismo invitado no regenera el token")
    void asecondOrderDoesNotRegenerateTheToken() throws Exception {
        Store store = createStore("inv-twice");
        Customer guest = checkoutAsGuest(store, "dosveces");
        String firstToken = guest.getInvitationToken();

        Product product = createProduct(store, createCategory(store, "Cat"), "Otro", BigDecimal.valueOf(5000));
        mockMvc.perform(checkout(store, product, guest.getEmail())).andExpect(status().isOk());

        // Quien compra tres veces no debe recibir tres invitaciones distintas, cada una invalidando la
        // anterior: markInvitedIfGuest ya lo condiciona a que no haya token.
        Customer after = customerRepository.findById(guest.getId()).orElseThrow();
        assertThat(after.getInvitationToken()).isEqualTo(firstToken);
    }

    // --- fixtures ----------------------------------------------------------------------------------

    private Customer checkoutAsGuest(Store store, String emailPrefix) throws Exception {
        // Una tienda nueva no cubre ninguna zona de envío, así que el checkout respondería 409 (A7). Lo
        // que se mide aquí es la invitación, no el envío.
        disableShipping(store);
        Product product = createProduct(store, createCategory(store, "Cat"), "Producto", BigDecimal.valueOf(10000));
        String email = emailPrefix + "-" + nextSeq() + "@test.local";
        mockMvc.perform(checkout(store, product, email)).andExpect(status().isOk());
        return customerRepository.findByStoreIdAndEmail(store.getId(), email).orElseThrow();
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder checkout(
            Store store, Product product, String email) {
        return post("/api/v1/checkout")
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
                        """.formatted(email, product.getId()));
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder accept(
            Store store, String token, String password) {
        return post("/api/customer/auth/accept-invitation")
                .header("Host", hostHeader(store))
                .contentType("application/json")
                .content("""
                        {"token":"%s","password":"%s"}""".formatted(token, password));
    }
}
