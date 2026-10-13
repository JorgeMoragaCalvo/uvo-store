package org.uvo.uvostore.admin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.uvo.uvostore.entity.shipping.ShippingMethod;
import org.uvo.uvostore.entity.shipping.enums.ShippingCarrier;
import org.uvo.uvostore.entity.shipping.enums.ShippingMethodType;
import org.uvo.uvostore.entity.security.User;
import org.uvo.uvostore.entity.tenant.Store;
import org.uvo.uvostore.repository.ShippingMethodRepository;
import org.uvo.uvostore.support.IntegrationTestSupport;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * PROD-12. Lo que se promete al excluir los transportistas incompletos, comprobado.
 *
 * <p><b>Esta clase NO lleva {@code @WithShippingCarriers} a propósito</b>: corre con el valor que se
 * despacha —ninguno homologado— mientras {@code AdminShippingCarrierTest} lo enciende en el suyo. Así el
 * criterio de PROD-12 depende de un test y no de un comentario.
 *
 * <p>Antes de esto, el panel ofrecía los dos transportistas y la administración aceptaba cualquiera de
 * los dos nombres del enum. Con Correos de Chile —un placeholder que siempre devuelve vacío— el método
 * quedaba activo y el checkout no lo mostraba nunca: el comerciante creía haber habilitado un despacho
 * que no existe.
 */
class AdminShippingCarrierGateTest extends IntegrationTestSupport {

    @Autowired
    private ShippingMethodRepository shippingMethodRepository;

    private record Fixture(Store store, String token) {
    }

    @Test
    @DisplayName("Sin transportistas homologados la lista que lee el panel viene vacía")
    void thePanelIsOfferedNoCarriers() throws Exception {
        Fixture fixture = fixture("gate-list");

        mockMvc.perform(get("/api/admin/shipping/carriers")
                        .header("Host", hostHeader(fixture.store()))
                        .header("Authorization", "Bearer " + fixture.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    @DisplayName("Correos de Chile no se puede configurar: no está implementado")
    void aPlaceholderCarrierIsRejected() throws Exception {
        Fixture fixture = fixture("gate-correos");

        mockMvc.perform(createMethod(fixture, "correos", "CORREOS_CHILE"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("no está implementada")));

        assertThat(shippingMethodRepository.findByStoreId(fixture.store().getId())).isEmpty();
    }

    @Test
    @DisplayName("Chilexpress sin homologar tampoco, y lo dice de otra manera")
    void anUnhomologatedCarrierIsRejected() throws Exception {
        Fixture fixture = fixture("gate-chilex");

        // El mensaje distingue los dos casos porque para el comerciante son distintos: uno no se arregla
        // configurando nada y el otro sí.
        mockMvc.perform(createMethod(fixture, "chilexpress", "CHILEXPRESS"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("no está habilitada")));

        assertThat(shippingMethodRepository.findByStoreId(fixture.store().getId())).isEmpty();
    }

    @Test
    @DisplayName("Un método manual se sigue creando igual")
    void aManualMethodIsUntouched() throws Exception {
        Fixture fixture = fixture("gate-manual");

        mockMvc.perform(post("/api/admin/shipping/methods")
                        .header("Host", hostHeader(fixture.store()))
                        .header("Authorization", "Bearer " + fixture.token())
                        .contentType("application/json")
                        .content("{\"name\":\"Despacho propio\",\"code\":\"propio\",\"type\":\"COURIER\","
                                + "\"hasApiIntegration\":false,\"active\":true,\"sortOrder\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hasApiIntegration").value(false))
                .andExpect(jsonPath("$.carrier").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    @DisplayName("Un método con transportista escrito antes del guarda no se puede reactivar")
    void aPreExistingCarrierMethodCannotBeReactivated() throws Exception {
        Fixture fixture = fixture("gate-legacy");
        // Escrito por el repositorio, no por la API: es la fila que ya estaba en la base cuando llegó el
        // guarda, y reactivarla es el único camino que quedaba para volver a cotizar con ella.
        ShippingMethod legacy = new ShippingMethod();
        legacy.setStore(fixture.store());
        legacy.setName("Chilexpress de antes");
        legacy.setCode("legacy-chilexpress");
        legacy.setType(ShippingMethodType.COURIER);
        legacy.setHasApiIntegration(true);
        legacy.setCarrier(ShippingCarrier.CHILEXPRESS);
        legacy.setActive(false);
        legacy.setSortOrder(0);
        legacy = shippingMethodRepository.save(legacy);

        mockMvc.perform(post("/api/admin/shipping/methods/" + legacy.getId() + "/toggle-status")
                        .header("Host", hostHeader(fixture.store()))
                        .header("Authorization", "Bearer " + fixture.token()))
                .andExpect(status().isBadRequest());

        assertThat(shippingMethodRepository.findById(legacy.getId()).orElseThrow().isActive()).isFalse();
    }

    @Test
    @DisplayName("Apagar uno sí se permite: lo contrario encerraría al comerciante")
    void deactivatingIsAlwaysAllowed() throws Exception {
        Fixture fixture = fixture("gate-off");
        ShippingMethod legacy = new ShippingMethod();
        legacy.setStore(fixture.store());
        legacy.setName("Chilexpress encendido");
        legacy.setCode("legacy-on");
        legacy.setType(ShippingMethodType.COURIER);
        legacy.setHasApiIntegration(true);
        legacy.setCarrier(ShippingCarrier.CHILEXPRESS);
        legacy.setActive(true);
        legacy.setSortOrder(0);
        legacy = shippingMethodRepository.save(legacy);

        mockMvc.perform(post("/api/admin/shipping/methods/" + legacy.getId() + "/toggle-status")
                        .header("Host", hostHeader(fixture.store()))
                        .header("Authorization", "Bearer " + fixture.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));
    }

    private Fixture fixture(String prefix) throws Exception {
        Store store = createStore(prefix);
        User admin = createAdmin(store, prefix);
        return new Fixture(store, loginAdmin(store, admin));
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder createMethod(
            Fixture fixture, String code, String carrier) {
        return post("/api/admin/shipping/methods")
                .header("Host", hostHeader(fixture.store()))
                .header("Authorization", "Bearer " + fixture.token())
                .contentType("application/json")
                .content("{\"name\":\"Método\",\"code\":\"" + code + "\",\"type\":\"COURIER\","
                        + "\"hasApiIntegration\":true,\"carrier\":\"" + carrier + "\",\"active\":true,"
                        + "\"sortOrder\":0}");
    }
}
