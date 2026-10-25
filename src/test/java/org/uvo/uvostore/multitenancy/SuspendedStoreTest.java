package org.uvo.uvostore.multitenancy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.uvo.uvostore.entity.security.User;
import org.uvo.uvostore.entity.tenant.Store;
import org.uvo.uvostore.entity.tenant.enums.StoreStatus;
import org.uvo.uvostore.support.IntegrationTestSupport;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * G5. {@code stores.status} existía desde V8 y no lo leía nadie: una tienda suspendida seguía
 * vendiendo, cobrando y emitiendo documentos igual que una activa. Desde V18 el estado es un enum
 * respaldado por un CHECK, y {@code TenantResolutionFilter} rechaza con 403 todo lo que llegue a una
 * tienda SUSPENDED — salvo {@code /api/platform/**}, que es por donde el operador la reactiva, y desde
 * PROD-23 los retornos y avisos de pago. Una tienda CLOSED no recibe ni eso.
 */
class SuspendedStoreTest extends IntegrationTestSupport {

    @Value("${app.platform-api-key}")
    private String platformKey;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("La tienda pública de una tienda suspendida responde 403, no su catálogo")
    void theStorefrontIsClosedForASuspendedStore() throws Exception {
        Store store = createStore("susp-storefront", StoreStatus.SUSPENDED);

        mockMvc.perform(get("/api/v1/products").header("Host", hostHeader(store)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("Esta tienda está suspendida"));
    }

    @Test
    @DisplayName("El checkout de una tienda suspendida se rechaza antes de tocar nada")
    void checkoutIsRefusedForASuspendedStore() throws Exception {
        Store store = createStore("susp-checkout", StoreStatus.SUSPENDED);

        // El cuerpo da igual: el rechazo ocurre en el filtro, antes de la validación y antes de
        // cualquier llamada a la pasarela. Eso es justo lo que se quiere — que no se cobre.
        mockMvc.perform(post("/api/v1/checkout")
                        .header("Host", hostHeader(store))
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Suspender la tienda cierra también su panel de administración, token válido incluido")
    void theAdminPanelIsBlockedToo() throws Exception {
        Store store = createStore("susp-admin", StoreStatus.ACTIVE);
        User admin = createAdmin(store, "admin-susp");

        // Se autentica mientras la tienda está activa: el token es legítimo y sigue siéndolo.
        String token = loginAdmin(store, admin);
        mockMvc.perform(get("/api/admin/products")
                        .header("Host", hostHeader(store))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        store.setStatus(StoreStatus.SUSPENDED);
        storeRepository.saveAndFlush(store);

        // Decisión explícita: se bloquea al comerciante también. Dejarlo entrar a ver por qué está
        // suspendido necesitaría una pantalla de facturación que no existe.
        mockMvc.perform(get("/api/admin/products")
                        .header("Host", hostHeader(store))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());

        // Y no puede volver a entrar tampoco.
        mockMvc.perform(post("/api/admin/auth/login")
                        .header("Host", hostHeader(store))
                        .contentType("application/json")
                        .content("{\"email\":\"" + admin.getEmail() + "\",\"password\":\"" + TEST_PASSWORD + "\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("/api/platform/** sigue pasando: es por donde el operador la reactiva")
    void platformRoutesStillGoThroughForASuspendedStore() throws Exception {
        Store store = createStore("susp-platform", StoreStatus.SUSPENDED);
        String domain = "reactivada-" + nextSeq() + ".cl";

        // Host apuntando a la tienda suspendida — el filtro la resuelve y aun así deja pasar.
        mockMvc.perform(put("/api/platform/stores/" + store.getId() + "/domain")
                        .header("Host", hostHeader(store))
                        .header("X-Platform-Key", platformKey)
                        .contentType("application/json")
                        .content("{\"domain\":\"" + domain + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.domain").value(domain));
    }

    @Test
    @DisplayName("PROD-23: con la tienda suspendida, los retornos y avisos de pago pasan; crear un pago, no")
    void paymentCallbacksStillGoThroughForASuspendedStore() throws Exception {
        Store store = createStore("susp-callbacks", StoreStatus.SUSPENDED);

        // Lo que pase después da igual (sin token no hay nada que confirmar, y sin firma el aviso se rechaza): lo que
        // importa es que el filtro no los corte. Un comprador que pagó justo antes de la suspensión necesita que su
        // pago se confirme.
        mockMvc.perform(post("/api/v1/webpay/return")
                        .header("Host", hostHeader(store))
                        .param("TBK_TOKEN", "abandonado"))
                .andExpect(notTheSuspendedRejection());
        mockMvc.perform(post("/api/v1/mercadopago/webhook")
                        .header("Host", hostHeader(store))
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(notTheSuspendedRejection());
        mockMvc.perform(post("/api/v1/stripe/webhook")
                        .header("Host", hostHeader(store))
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(notTheSuspendedRejection());

        mockMvc.perform(post("/api/v1/webpay/create")
                        .header("Host", hostHeader(store))
                        .contentType("application/json")
                        .content("{\"orderId\":1}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("Esta tienda está suspendida"));
    }

    @Test
    @DisplayName("PROD-23: una tienda cerrada responde 403 a todo, retornos de pago incluidos; la plataforma sigue pasando")
    void aClosedStoreRefusesEverythingButThePlatform() throws Exception {
        Store store = createStore("susp-closed", StoreStatus.CLOSED);

        mockMvc.perform(get("/api/v1/products").header("Host", hostHeader(store)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("Esta tienda está cerrada"));
        for (String callback : new String[]{"/api/v1/webpay/return", "/api/v1/mercadopago/webhook", "/api/v1/stripe/webhook"}) {
            mockMvc.perform(post(callback)
                            .header("Host", hostHeader(store))
                            .param("TBK_TOKEN", "abandonado"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.message").value("Esta tienda está cerrada"));
        }

        mockMvc.perform(get("/api/platform/stores/" + store.getId())
                        .header("Host", hostHeader(store))
                        .header("X-Platform-Key", platformKey))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"));
    }

    private static org.springframework.test.web.servlet.ResultMatcher notTheSuspendedRejection() {
        return result -> {
            org.assertj.core.api.Assertions.assertThat(result.getResponse().getStatus()).isNotEqualTo(403);
            org.assertj.core.api.Assertions.assertThat(result.getResponse().getContentAsString())
                    .doesNotContain("Esta tienda está suspendida");
        };
    }

    @Test
    @DisplayName("Una tienda activa no cambia en nada")
    void anActiveStoreIsUnaffected() throws Exception {
        Store store = createStore("susp-control", StoreStatus.ACTIVE);

        mockMvc.perform(get("/api/v1/products").header("Host", hostHeader(store)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("La base rechaza un estado que no es del enum")
    void theDatabaseRefusesAStatusOutsideTheEnum() {
        Store store = createStore("susp-check", StoreStatus.ACTIVE);
        storeRepository.saveAndFlush(store);

        // El CHECK de V18 es la mitad que no depende de que el escritor sea Hibernate: una migración
        // de datos o un UPDATE a mano en producción ya no pueden dejar la columna en un valor que
        // luego reviente al leerla.
        assertThatThrownBy(() ->
                jdbcTemplate.update("UPDATE stores SET status = 'INVENTADO' WHERE id = ?", store.getId()))
                .hasMessageContaining("stores_status_valid");
    }
}
