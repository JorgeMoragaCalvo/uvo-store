package org.uvo.uvostore.platform;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.uvo.uvostore.entity.security.User;
import org.uvo.uvostore.entity.tenant.Store;
import org.uvo.uvostore.entity.tenant.enums.StoreStatus;
import org.uvo.uvostore.support.AuditRows;
import org.uvo.uvostore.support.IntegrationTestSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * PROD-23. Suspender y reactivar una tienda desde la plataforma. Hasta aquí SUSPENDED existía (G5) pero solo se ponía
 * con SQL; ahora es una operación auditada, idempotente (el POS reintenta) y sin vuelta atrás una vez cerrada.
 */
class StoreLifecycleTest extends IntegrationTestSupport {

    @Value("${app.platform-api-key}")
    private String platformKey;

    @Test
    @DisplayName("Suspender cierra la tienda y deja una fila con el estado, el motivo y el operador")
    void suspendingClosesTheStoreAndIsAudited() throws Exception {
        Store store = createStore("life-susp");

        mockMvc.perform(platform(post("/api/platform/stores/" + store.getId() + "/suspend"))
                        .header("X-Platform-Operator", "op-baja")
                        .content("{\"reason\":\"MODULE_DEACTIVATED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUSPENDED"))
                .andExpect(jsonPath("$.statusChangedAt").isNotEmpty());

        mockMvc.perform(get("/api/v1/products").header("Host", hostHeader(store)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("Esta tienda está suspendida"));
        mockMvc.perform(platform(get("/api/platform/stores/" + store.getId())).header("Host", hostHeader(store)))
                .andExpect(status().isOk());

        AuditRows.Row row = auditRow(store, "STORE_STATUS_UPDATE");
        assertThat(row.actorType()).isEqualTo("PLATFORM");
        assertThat(row.actorLabel()).isEqualTo("op-baja");
        assertThat(row.targetType()).isEqualTo("STORE");
        assertThat(row.targetId()).isEqualTo(store.getId());
        assertThat(row.change("status").path("from").asText()).isEqualTo("ACTIVE");
        assertThat(row.change("status").path("to").asText()).isEqualTo("SUSPENDED");
        assertThat(row.json().path("reason").asText()).isEqualTo("MODULE_DEACTIVATED");
    }

    @Test
    @DisplayName("Suspender dos veces responde lo mismo y deja una sola fila: los reintentos del POS no fallan")
    void suspendingTwiceIsANoOp() throws Exception {
        Store store = createStore("life-twice");

        for (int i = 0; i < 2; i++) {
            mockMvc.perform(platform(post("/api/platform/stores/" + store.getId() + "/suspend"))
                            .content("{\"reason\":\"NON_PAYMENT\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("SUSPENDED"));
        }

        assertThat(auditRows(store, "STORE_STATUS_UPDATE")).hasSize(1);
    }

    @Test
    @DisplayName("Sin motivo, con un motivo inventado o con una tienda que no existe, no se suspende nada")
    void suspendingNeedsAReasonAndAStore() throws Exception {
        Store store = createStore("life-noreason");

        mockMvc.perform(platform(post("/api/platform/stores/" + store.getId() + "/suspend")).content("{}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(platform(post("/api/platform/stores/" + store.getId() + "/suspend"))
                        .content("{\"reason\":\"PORQUE_SI\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(platform(post("/api/platform/stores/999999999/suspend")).content("{\"reason\":\"OTHER\"}"))
                .andExpect(status().isNotFound());

        assertThat(storeRepository.findById(store.getId()).orElseThrow().getStatus()).isEqualTo(StoreStatus.ACTIVE);
        assertThat(auditRows(store)).isEmpty();
    }

    @Test
    @DisplayName("Reactivar devuelve la tienda, y la sesión que tenía el admin vuelve a servir")
    void reactivatingRestoresTheStoreAndItsSessions() throws Exception {
        Store store = createStore("life-react");
        User admin = createAdmin(store, "life-react");
        String token = loginAdmin(store, admin);
        mockMvc.perform(platform(post("/api/platform/stores/" + store.getId() + "/suspend"))
                        .content("{\"reason\":\"NON_PAYMENT\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/admin/products").header("Host", hostHeader(store))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());

        mockMvc.perform(platform(post("/api/platform/stores/" + store.getId() + "/reactivate")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        // La suspensión no cierra sesiones: bloquea por Host, y al reactivar el token vigente vuelve a valer.
        mockMvc.perform(get("/api/admin/products").header("Host", hostHeader(store))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        AuditRows.Row back = auditRows(store, "STORE_STATUS_UPDATE").getLast();
        assertThat(back.change("status").path("from").asText()).isEqualTo("SUSPENDED");
        assertThat(back.change("status").path("to").asText()).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("Reactivar una tienda activa no cambia nada ni deja fila")
    void reactivatingAnActiveStoreIsANoOp() throws Exception {
        Store store = createStore("life-active");

        mockMvc.perform(platform(post("/api/platform/stores/" + store.getId() + "/reactivate")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.statusChangedAt").isEmpty());

        assertThat(auditRows(store)).isEmpty();
    }

    @Test
    @DisplayName("Con la tienda cerrada no se suspende, ni se reactiva, ni se toca su dominio ni su dueño")
    void aClosedStoreAcceptsNoOperation() throws Exception {
        Store store = createStore("life-closed", StoreStatus.CLOSED);
        String id = String.valueOf(store.getId());

        mockMvc.perform(platform(post("/api/platform/stores/" + id + "/suspend")).content("{\"reason\":\"OTHER\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("cerrada")));
        mockMvc.perform(platform(post("/api/platform/stores/" + id + "/reactivate")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("cerrada")));
        mockMvc.perform(platform(put("/api/platform/stores/" + id + "/domain")).content("{\"domain\":\"otra.example.com\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("cerrada")));
        mockMvc.perform(platform(put("/api/platform/stores/" + id + "/domain/verified")).content("{\"verified\":false}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("cerrada")));
        mockMvc.perform(platform(post("/api/platform/stores/" + id + "/owner/access-recovery")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("cerrada")));

        assertThat(storeRepository.findById(store.getId()).orElseThrow().getStatus()).isEqualTo(StoreStatus.CLOSED);
        assertThat(auditRows(store)).isEmpty();
    }

    private MockHttpServletRequestBuilder platform(MockHttpServletRequestBuilder builder) {
        return builder.header("X-Platform-Key", platformKey).contentType("application/json");
    }
}
