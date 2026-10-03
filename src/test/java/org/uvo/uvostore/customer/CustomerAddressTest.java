package org.uvo.uvostore.customer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.uvo.uvostore.entity.customer.Customer;
import org.uvo.uvostore.entity.security.User;
import org.uvo.uvostore.entity.tenant.Store;
import org.uvo.uvostore.support.IntegrationTestSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * F23. La dirección que se pide como predeterminada queda marcada como tal.
 *
 * <p>{@code applyCommonFields} copiaba los once campos de texto y se dejaba {@code isDefault}, mientras el
 * {@code if (command.isDefault())} que desmarca <b>las demás</b> sí estaba. Así que se ejecutaba la mitad
 * destructiva de la operación y no la constructiva: pedir "que esta sea la predeterminada" <b>borraba la
 * que había</b> sin marcar la nueva, y el cliente se quedaba sin ninguna.
 *
 * <p>Primera cobertura de la superficie de direcciones — no tenía ninguna, que es el mismo patrón de F20
 * (informes) y F22 (cuenta del cliente): lo que no se prueba es donde estaban los fallos.
 */
class CustomerAddressTest extends IntegrationTestSupport {

    @Test
    @DisplayName("Crear una dirección como predeterminada la marca como tal")
    void creatingADefaultAddressMarksIt() throws Exception {
        Fixture f = fixture("addr-create");

        mockMvc.perform(create(f, "Primera", true))
                .andExpect(status().isOk())
                // Esto respondía false.
                .andExpect(jsonPath("$.isDefault").value(true));

        mockMvc.perform(list(f))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].isDefault").value(true));
    }

    @Test
    @DisplayName("Una segunda predeterminada releva a la primera, y queda exactamente una")
    void asecondDefaultReplacesTheFirst() throws Exception {
        Fixture f = fixture("addr-replace");
        long first = idOf(create(f, "Primera", true));

        long second = idOf(create(f, "Segunda", true));

        // El fallo entero: aquí quedaban CERO predeterminadas, porque la nueva no se marcaba y la vieja
        // sí se desmarcaba.
        assertThat(defaultIds(f)).containsExactly(second);
        assertThat(defaultIds(f)).doesNotContain(first);
    }

    @Test
    @DisplayName("Editar una dirección para hacerla predeterminada la marca y desmarca la otra")
    void editingAnAddressToBecomeDefault() throws Exception {
        Fixture f = fixture("addr-edit-on");
        long first = idOf(create(f, "Primera", true));
        long second = idOf(create(f, "Segunda", false));

        mockMvc.perform(update(f, second, "Segunda", true))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isDefault").value(true));

        assertThat(defaultIds(f)).containsExactly(second);
        assertThat(defaultIds(f)).doesNotContain(first);
    }

    @Test
    @DisplayName("Editar quitando la bandera deja de ser predeterminada")
    void editingAnAddressToStopBeingDefault() throws Exception {
        Fixture f = fixture("addr-edit-off");
        long only = idOf(create(f, "Unica", true));

        // Esto se ignoraba por completo: la bandera solo se podía encender por el endpoint aparte y no
        // había forma de apagarla desde el formulario.
        mockMvc.perform(update(f, only, "Unica", false))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isDefault").value(false));

        assertThat(defaultIds(f)).isEmpty();
    }

    @Test
    @DisplayName("Borrar la predeterminada no deja ninguna, y se puede volver a elegir")
    void deletingTheDefaultLeavesNoneAndOneCanBeChosenAgain() throws Exception {
        Fixture f = fixture("addr-delete");
        long first = idOf(create(f, "Primera", true));
        long second = idOf(create(f, "Segunda", false));

        mockMvc.perform(delete("/api/customer/addresses/" + first)
                        .header("Host", hostHeader(f.store))
                        .header("Authorization", "Bearer " + f.customerToken))
                .andExpect(status().isNoContent());

        // No se promociona a otra, igual que el original: la invariante es "como máximo una".
        assertThat(defaultIds(f)).isEmpty();

        mockMvc.perform(post("/api/customer/addresses/" + second + "/default")
                        .header("Host", hostHeader(f.store))
                        .header("Authorization", "Bearer " + f.customerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isDefault").value(true));

        assertThat(defaultIds(f)).containsExactly(second);
    }

    @Test
    @DisplayName("El endpoint del panel marca la misma predeterminada que el del cliente")
    void theAdminEndpointSetsTheSameDefault() throws Exception {
        Fixture f = fixture("addr-admin");
        long first = idOf(create(f, "Primera", true));
        long second = idOf(create(f, "Segunda", false));

        User admin = createAdmin(f.store, "addr-admin");
        String adminToken = loginAdmin(f.store, admin);

        // Las dos superficies delegan en el mismo CustomerAddressServiceImpl.setDefaultAddress; esto lo
        // fija para que no se dupliquen más adelante.
        mockMvc.perform(post("/api/admin/customers/" + f.customer.getId() + "/addresses/" + second + "/default")
                        .header("Host", hostHeader(f.store))
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isDefault").value(true));

        assertThat(defaultIds(f)).containsExactly(second);
        assertThat(defaultIds(f)).doesNotContain(first);
    }

    @Test
    @DisplayName("La dirección de otro cliente no se puede tocar")
    void anotherCustomersAddressIsOffLimits() throws Exception {
        Fixture mine = fixture("addr-mine");
        Fixture theirs = fixture("addr-theirs");
        long theirAddress = idOf(create(theirs, "Ajena", false));

        // findOwnedOrThrow lanza AccessDeniedException, y no tenía ninguna prueba.
        mockMvc.perform(update(mine, theirAddress, "Robada", true))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/customer/addresses/" + theirAddress + "/default")
                        .header("Host", hostHeader(mine.store))
                        .header("Authorization", "Bearer " + mine.customerToken))
                .andExpect(status().isForbidden());
    }

    // --- fixtures ----------------------------------------------------------------------------------

    private record Fixture(Store store, Customer customer, String customerToken) {
    }

    private Fixture fixture(String prefix) throws Exception {
        Store store = createStore(prefix);
        Customer customer = createCustomer(store, prefix);
        return new Fixture(store, customer, loginCustomer(store, customer));
    }

    private org.springframework.test.web.servlet.RequestBuilder create(Fixture f, String name, boolean isDefault) {
        return post("/api/customer/addresses")
                .header("Host", hostHeader(f.store))
                .header("Authorization", "Bearer " + f.customerToken)
                .contentType("application/json")
                .content(body(name, isDefault));
    }

    private org.springframework.test.web.servlet.RequestBuilder update(Fixture f, long addressId, String name, boolean isDefault) {
        return put("/api/customer/addresses/" + addressId)
                .header("Host", hostHeader(f.store))
                .header("Authorization", "Bearer " + f.customerToken)
                .contentType("application/json")
                .content(body(name, isDefault));
    }

    private org.springframework.test.web.servlet.RequestBuilder list(Fixture f) {
        return get("/api/customer/addresses")
                .header("Host", hostHeader(f.store))
                .header("Authorization", "Bearer " + f.customerToken);
    }

    private String body(String name, boolean isDefault) {
        return """
                {"firstName":"%s","lastName":"Comprador","addressLine1":"Calle 1","city":"Santiago",\
                "state":"RM","postalCode":"8320000","country":"CL","phone":"+56911111111","isDefault":%s}"""
                .formatted(name, isDefault);
    }

    private long idOf(org.springframework.test.web.servlet.RequestBuilder request) throws Exception {
        String response = mockMvc.perform(request)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).get("id").asLong();
    }

    /** Los ids marcados como predeterminados según la propia API, que es lo que ve el cliente. */
    private java.util.List<Long> defaultIds(Fixture f) throws Exception {
        String response = mockMvc.perform(list(f))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        java.util.List<Long> ids = new java.util.ArrayList<>();
        for (com.fasterxml.jackson.databind.JsonNode node : objectMapper.readTree(response)) {
            if (node.get("isDefault").asBoolean()) {
                ids.add(node.get("id").asLong());
            }
        }
        return ids;
    }
}
