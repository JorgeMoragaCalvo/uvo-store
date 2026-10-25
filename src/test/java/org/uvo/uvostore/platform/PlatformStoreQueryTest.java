package org.uvo.uvostore.platform;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.uvo.uvostore.entity.pos.PosConnection;
import org.uvo.uvostore.entity.security.User;
import org.uvo.uvostore.entity.tenant.Store;
import org.uvo.uvostore.entity.tenant.enums.StoreStatus;
import org.uvo.uvostore.repository.PosConnectionRepository;
import org.uvo.uvostore.support.IntegrationTestSupport;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * PROD-23. Lo que la plataforma lee: la lista de tiendas, el detalle de una y su auditoría. El consumidor es el
 * backoffice del POS, y nada de esto puede servir para ver secretos ni la auditoría de otra tienda.
 */
class PlatformStoreQueryTest extends IntegrationTestSupport {

    @Value("${app.platform-api-key}")
    private String platformKey;

    @Autowired
    private PosConnectionRepository connectionRepository;

    @Test
    @DisplayName("La búsqueda encuentra por nick, nombre, dominio y correo del dueño, sin distinguir mayúsculas")
    void theSearchCoversSlugNameDomainAndOwnerEmail() throws Exception {
        String token = "qbusca" + nextSeq();
        Store bySlug = createStore(token + "-slug");
        Store byName = createStore("qname");
        byName.setName("Tienda " + token.toUpperCase());
        storeRepository.save(byName);
        Store byDomain = createStore("qdomain");
        byDomain.setDomain(token + ".example.com");
        storeRepository.save(byDomain);
        Store byOwner = createStore("qowner");
        User owner = createAdmin(byOwner, token + "-dueno");
        byOwner.setOwnerUserId(owner.getId());
        storeRepository.save(byOwner);
        createStore("qotra");

        JsonNode page = list("?search=" + token.toUpperCase());

        assertThat(ids(page)).containsExactlyInAnyOrder(bySlug.getId(), byName.getId(), byDomain.getId(), byOwner.getId());
        assertThat(page.get("totalElements").asLong()).isEqualTo(4);
    }

    @Test
    @DisplayName("Los filtros por estado y por empresa del POS se combinan con la búsqueda")
    void statusAndCompanyFilters() throws Exception {
        String token = "qfiltro" + nextSeq();
        Store active = createStore(token + "-a");
        Store suspended = createStore(token + "-s", StoreStatus.SUSPENDED);
        long companyId = 870_000_000L + nextSeq();
        connection(active, companyId, "uvp_" + companyId + "_secreto");

        assertThat(ids(list("?search=" + token + "&status=SUSPENDED"))).containsExactly(suspended.getId());
        assertThat(ids(list("?companyId=" + companyId))).containsExactly(active.getId());
        mockMvc.perform(platform(get("/api/platform/stores?status=INVENTADO"))).andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Veinte por página, en orden, y un orden que no está en la lista blanca vuelve a la fecha de alta")
    void pagingAndSorting() throws Exception {
        String token = "qpagina" + nextSeq();
        List<Long> created = new ArrayList<>();
        for (int i = 0; i < 21; i++) {
            created.add(createStore(token + "-" + (char) ('a' + i)).getId());
        }

        JsonNode first = list("?search=" + token + "&sortField=slug&sortDirection=asc");
        JsonNode second = list("?search=" + token + "&sortField=slug&sortDirection=asc&page=2");
        assertThat(first.get("totalElements").asLong()).isEqualTo(21);
        assertThat(ids(first)).hasSize(20).containsExactlyElementsOf(created.subList(0, 20));
        assertThat(ids(second)).containsExactly(created.get(20));

        // Una columna cualquiera (aquí una que no existe) no es un 500: vuelve al orden por fecha de alta.
        mockMvc.perform(platform(get("/api/platform/stores?search=" + token + "&sortField=ownerUserId")))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Ni la lista ni el detalle llevan los secretos ni la URL del POS; sí el dueño y si tiene invitación pendiente")
    void noSecretsAndTheOwnerState() throws Exception {
        Store store = createStore("qsecret");
        User owner = createAdmin(store, "qsecret-dueno");
        owner.setPassword(null);
        owner.setInvitationToken("tok-" + nextSeq() + "-" + "y".repeat(40));
        owner.setInvitationSentAt(Instant.now());
        userRepository.save(owner);
        store.setOwnerUserId(owner.getId());
        storeRepository.save(store);
        long companyId = 870_000_000L + nextSeq();
        connection(store, companyId, "uvp_" + companyId + "_clave-que-no-sale");

        String detail = mockMvc.perform(platform(get("/api/platform/stores/" + store.getId())))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String listing = mockMvc.perform(platform(get("/api/platform/stores?search=" + store.getSlug())))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        for (String json : List.of(detail, listing)) {
            assertThat(json).doesNotContain("clave-que-no-sale", "secreto-webhook-que-no-sale", "pos.que-no-sale.local",
                    owner.getInvitationToken());
        }
        JsonNode node = objectMapper.readTree(detail);
        assertThat(node.path("owner").path("email").asText()).isEqualTo(owner.getEmail());
        assertThat(node.path("owner").path("invitationPending").asBoolean()).isTrue();
        assertThat(node.path("posConnections").get(0).path("companyId").asLong()).isEqualTo(companyId);
        assertThat(node.path("posConnections").get(0).path("active").asBoolean()).isTrue();
        assertThat(node.path("status").asText()).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("El detalle de una tienda que no existe es un 404")
    void anUnknownStoreIsNotFound() throws Exception {
        mockMvc.perform(platform(get("/api/platform/stores/999999999"))).andExpect(status().isNotFound());
        mockMvc.perform(platform(get("/api/platform/stores/999999999/audit-events"))).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("La auditoría es solo de la tienda pedida, de lo más nuevo a lo más viejo, y se filtra por acción y fechas")
    void theAuditBelongsToTheStoreAndCanBeFiltered() throws Exception {
        Store store = createStore("qaudit");
        Store other = createStore("qaudit-otra");
        operate(store, "/suspend", "{\"reason\":\"NON_PAYMENT\"}");
        operate(store, "/reactivate", null);
        operate(other, "/suspend", "{\"reason\":\"OTHER\"}");

        JsonNode all = audit(store, "");
        assertThat(all.get("totalElements").asLong()).isEqualTo(2);
        List<String> actions = new ArrayList<>();
        all.get("content").forEach(row -> {
            assertThat(row.path("storeId").asLong()).isEqualTo(store.getId());
            actions.add(row.path("details").path("changes").path("status").path("to").asText());
        });
        // Lo último primero: la reactivación.
        assertThat(actions).containsExactly("ACTIVE", "SUSPENDED");

        assertThat(audit(store, "?action=STORE_CLOSE").get("totalElements").asLong()).isZero();
        assertThat(audit(store, "?action=STORE_STATUS_UPDATE").get("totalElements").asLong()).isEqualTo(2);
        assertThat(audit(store, "?from=" + Instant.now().plusSeconds(3600)).get("totalElements").asLong()).isZero();
        assertThat(audit(store, "?to=" + Instant.now().plusSeconds(3600)).get("totalElements").asLong()).isEqualTo(2);

        mockMvc.perform(platform(get("/api/platform/stores/" + store.getId() + "/audit-events?action=INVENTADA")))
                .andExpect(status().isBadRequest());
        mockMvc.perform(platform(get("/api/platform/stores/" + store.getId() + "/audit-events?from="
                        + Instant.now() + "&to=" + Instant.now().minusSeconds(60))))
                .andExpect(status().isBadRequest());
    }

    private void operate(Store store, String path, String body) throws Exception {
        MockHttpServletRequestBuilder request = platform(post("/api/platform/stores/" + store.getId() + path));
        if (body != null) {
            request.content(body);
        }
        mockMvc.perform(request).andExpect(status().isOk());
    }

    private PosConnection connection(Store store, long companyId, String apiKey) {
        PosConnection connection = new PosConnection();
        connection.setStore(store);
        connection.setCompanyName("Empresa consulta");
        connection.setCompanyId(companyId);
        connection.setApiUrl("https://pos.que-no-sale.local/api");
        connection.setApiKey(apiKey);
        connection.setWebhookSecret("secreto-webhook-que-no-sale");
        connection.setActive(true);
        return connectionRepository.save(connection);
    }

    private JsonNode list(String query) throws Exception {
        return objectMapper.readTree(mockMvc.perform(platform(get("/api/platform/stores" + query)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }

    private JsonNode audit(Store store, String query) throws Exception {
        return objectMapper.readTree(mockMvc.perform(platform(get("/api/platform/stores/" + store.getId() + "/audit-events" + query)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }

    private static List<Long> ids(JsonNode page) {
        List<Long> ids = new ArrayList<>();
        page.get("content").forEach(store -> ids.add(store.get("id").asLong()));
        return ids;
    }

    private MockHttpServletRequestBuilder platform(MockHttpServletRequestBuilder builder) {
        return builder.header("X-Platform-Key", platformKey).contentType("application/json");
    }
}
