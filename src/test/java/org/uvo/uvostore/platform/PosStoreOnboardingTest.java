package org.uvo.uvostore.platform;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.uvo.uvostore.entity.catalog.Product;
import org.uvo.uvostore.entity.pos.PosConnection;
import org.uvo.uvostore.entity.security.User;
import org.uvo.uvostore.entity.tenant.Store;
import org.uvo.uvostore.repository.PosConnectionRepository;
import org.uvo.uvostore.service.platform.StoreOwnerInvitedEvent;
import org.uvo.uvostore.support.AuditRows;
import org.uvo.uvostore.support.IntegrationTestSupport;
import org.uvo.uvostore.support.WithPosIntegration;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * POS-6. El alta de una tienda desde UvoPOS: cuando una empresa activa el módulo, su backend llama a
 * {@code POST /api/platform/stores} con el bloque {@code pos}, y en el mismo paso se crean la tienda, la conexión con
 * los secretos que generó el POS, los ajustes que la hacen operativa y la invitación del dueño.
 *
 * <p>Lo que más importa es que el alta sea <b>idempotente</b>: el POS la reintenta si no le llega la respuesta, y un
 * reintento no puede duplicar nada ni pisar lo que hay. La carrera entre dos reintentos simultáneos y el correo
 * después del commit están en {@code StoreOnboardingCommitTest}, que confirma transacciones.
 */
@WithPosIntegration
@RecordApplicationEvents
class PosStoreOnboardingTest extends IntegrationTestSupport {

    @Value("${app.platform-api-key}")
    private String platformKey;

    @Autowired
    private PosConnectionRepository connectionRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private ApplicationEvents events;
    @PersistenceContext
    private EntityManager entityManager;

    @Test
    @DisplayName("Crea la tienda, la conexión con los secretos del POS, sus ajustes y el dueño invitado")
    void createsTheStoreTheConnectionTheSettingsAndAPendingOwner() throws Exception {
        Alta alta = Alta.nueva();

        mockMvc.perform(onboard(alta.body()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(true))
                .andExpect(jsonPath("$.slug").value(alta.slug()));

        Store store = storeRepository.findBySlug(alta.slug()).orElseThrow();
        PosConnection connection = connectionRepository.findByCompanyId(alta.companyId()).orElseThrow();
        assertThat(connection.getStore().getId()).isEqualTo(store.getId());
        assertThat(connection.isActive()).isTrue();
        assertThat(connection.getConnectedAt()).isNotNull();
        assertThat(connection.getApiUrl()).isEqualTo(alta.apiUrl());
        assertThat(connection.getApiKey()).isEqualTo(alta.apiKey());
        assertThat(connection.getWebhookSecret()).isEqualTo(alta.webhookSecret());

        // Sin pos_sync_enabled, PosIntegrationPolicy trata la tienda como apagada aunque tenga conexión.
        assertThat(settingRepository.findByStoreIdAndSettingKey(store.getId(), "pos_sync_enabled"))
                .hasValueSatisfying(s -> assertThat(s.getValue()).isEqualTo("true"));
        assertThat(settingRepository.findByStoreIdAndSettingKey(store.getId(), "pos_api_url"))
                .hasValueSatisfying(s -> assertThat(s.getValue()).isEqualTo(alta.apiUrl()));

        User owner = userRepository.findById(store.getOwnerUserId()).orElseThrow();
        assertThat(owner.getEmail()).isEqualTo(alta.email());
        assertThat(owner.getPassword()).isNull();
        assertThat(owner.getInvitationToken()).isNotNull();
        assertThat(events.stream(StoreOwnerInvitedEvent.class))
                .singleElement().satisfies(event -> assertThat(event.userId()).isEqualTo(owner.getId()));
    }

    @Test
    @DisplayName("Los secretos se guardan cifrados y la respuesta no los lleva")
    void theSecretsAreEncryptedAtRestAndNeverReturned() throws Exception {
        Alta alta = Alta.nueva();

        String response = mockMvc.perform(onboard(alta.body()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // Los generó el POS y ya los tiene: devolverlos solo los pondría en otro log.
        assertThat(response).doesNotContain(alta.apiKey()).doesNotContain(alta.webhookSecret());

        entityManager.flush();
        String storedKey = jdbcTemplate.queryForObject(
                "select api_key from pos_connections where company_id = ?", String.class, alta.companyId());
        String storedSecret = jdbcTemplate.queryForObject(
                "select webhook_secret from pos_connections where company_id = ?", String.class, alta.companyId());
        assertThat(storedKey).isNotEqualTo(alta.apiKey()).doesNotContain(alta.apiKey());
        assertThat(storedSecret).isNotEqualTo(alta.webhookSecret()).doesNotContain(alta.webhookSecret());
    }

    @Test
    @DisplayName("Un reintento con el mismo cuerpo devuelve la misma tienda y no duplica nada")
    void aRetryWithTheSameBodyDuplicatesNothing() throws Exception {
        Alta alta = Alta.nueva();
        String first = mockMvc.perform(onboard(alta.body()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        long stores = storeRepository.count();
        long users = userRepository.count();
        long roles = roleRepository.count();
        long connections = connectionRepository.count();
        long settings = settingRepository.count();

        String second = mockMvc.perform(onboard(alta.body()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(false))
                .andReturn().getResponse().getContentAsString();

        assertThat(objectMapper.readTree(second).get("storeId").asLong())
                .isEqualTo(objectMapper.readTree(first).get("storeId").asLong());
        assertThat(objectMapper.readTree(second).get("adminUserId").asLong())
                .isEqualTo(objectMapper.readTree(first).get("adminUserId").asLong());
        assertThat(storeRepository.count()).isEqualTo(stores);
        assertThat(userRepository.count()).isEqualTo(users);
        assertThat(roleRepository.count()).isEqualTo(roles);
        assertThat(connectionRepository.count()).isEqualTo(connections);
        assertThat(settingRepository.count()).isEqualTo(settings);
        // Y una sola invitación: el reintento no le manda otro correo al dueño.
        assertThat(events.stream(StoreOwnerInvitedEvent.class)).hasSize(1);
        // PROD-18. Y una sola fila de alta: el reintento no escribe nada, tampoco en la auditoría.
        assertThat(AuditRows.forStore(jdbcTemplate, objectMapper.readTree(first).get("storeId").asLong(), "STORE_CREATE"))
                .singleElement()
                .satisfies(row -> assertThat(row.json().path("posCompanyId").asLong()).isEqualTo(alta.companyId()));
    }

    @Test
    @DisplayName("Un reintento con otros secretos se rechaza y no cambia la conexión")
    void aRetryWithOtherSecretsIsRejectedAndChangesNothing() throws Exception {
        // Si el reintento actualizara los secretos, uno atrasado de una activación anterior volvería a poner unos que
        // el POS ya no tiene, y ninguna llamada autenticaría más.
        Alta alta = Alta.nueva();
        mockMvc.perform(onboard(alta.body())).andExpect(status().isOk());

        Alta otherSecrets = alta.withSecrets("uvp_" + alta.companyId() + "_" + "b".repeat(40), "t".repeat(40));
        mockMvc.perform(onboard(otherSecrets.body()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("otras credenciales")));

        PosConnection connection = connectionRepository.findByCompanyId(alta.companyId()).orElseThrow();
        entityManager.refresh(connection);
        assertThat(connection.getApiKey()).isEqualTo(alta.apiKey());
        assertThat(connection.getWebhookSecret()).isEqualTo(alta.webhookSecret());
    }

    @Test
    @DisplayName("Un reintento con otro correo de dueño se rechaza")
    void aRetryWithAnotherOwnerEmailIsRejected() throws Exception {
        Alta alta = Alta.nueva();
        mockMvc.perform(onboard(alta.body())).andExpect(status().isOk());

        mockMvc.perform(onboard(alta.withEmail("otro-" + alta.email()).body()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("otras credenciales")));
    }

    @Test
    @DisplayName("El correo del dueño se compara sin distinguir mayúsculas")
    void theOwnerEmailIsComparedIgnoringCase() throws Exception {
        Alta alta = Alta.nueva();
        mockMvc.perform(onboard(alta.body())).andExpect(status().isOk());

        mockMvc.perform(onboard(alta.withEmail(alta.email().toUpperCase()).body()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(false));
    }

    @Test
    @DisplayName("Una empresa que ya tiene tienda no puede dar de alta otra")
    void aCompanyCannotOnboardASecondStore() throws Exception {
        Alta alta = Alta.nueva();
        mockMvc.perform(onboard(alta.body())).andExpect(status().isOk());
        String otherSlug = "pos6-otra-" + nextSeq();

        mockMvc.perform(onboard(alta.withSlug(otherSlug).body()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("ya tiene la tienda " + alta.slug())));

        assertThat(storeRepository.existsBySlug(otherSlug)).isFalse();
    }

    @Test
    @DisplayName("Una empresa nueva con el nick de otra tienda recibe el error de siempre")
    void aNewCompanyWithATakenSlugIsRejected() throws Exception {
        Store existing = createStore("pos6-ocupada");

        mockMvc.perform(onboard(Alta.nueva().withSlug(existing.getSlug()).body()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Ese nick ya está en uso"));
    }

    @Test
    @DisplayName("El token tiene que ser de la empresa declarada y con una clave de máquina")
    void theApiKeyMustBelongToTheDeclaredCompany() throws Exception {
        Alta alta = Alta.nueva();

        // De otra empresa: con él, el POS de la empresa declarada no autenticaría nunca.
        mockMvc.perform(onboard(alta.withSecrets("uvp_" + (alta.companyId() + 1) + "_" + "a".repeat(40),
                        alta.webhookSecret()).body()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("empresa " + alta.companyId())));

        // Corta: lo genera una máquina, no lo teclea nadie.
        mockMvc.perform(onboard(alta.withSecrets("uvp_" + alta.companyId() + "_corta", alta.webhookSecret()).body()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("32")));

        assertThat(storeRepository.existsBySlug(alta.slug())).isFalse();
        assertThat(connectionRepository.findByCompanyId(alta.companyId())).isEmpty();
    }

    static Stream<Arguments> invalidPosBlocks() {
        Alta base = Alta.nueva();
        return Stream.of(
                Arguments.of("secreto corto", base.withSecrets(base.apiKey(), "corto-secreto").body(), "corto-secreto"),
                Arguments.of("sin empresa", base.body().replace("\"companyId\": " + base.companyId() + ",", ""), null),
                Arguments.of("URL que no es http", base.withApiUrl("ftp://pos.internal").body(), null),
                Arguments.of("sin token", base.body().replace("\"apiKey\": \"" + base.apiKey() + "\",", ""), null));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidPosBlocks")
    @DisplayName("Un bloque pos mal formado responde 400, sin crear nada y sin repetir el valor recibido")
    void anInvalidPosBlockIsRejected(String label, String body, String secretValue) throws Exception {
        String response = mockMvc.perform(onboard(body))
                .andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString();

        if (secretValue != null) {
            assertThat(response).doesNotContain(secretValue);
        }
        String slug = objectMapper.readTree(body).get("slug").asText();
        assertThat(storeRepository.existsBySlug(slug)).isFalse();
    }

    @Test
    @DisplayName("Con el token generado, el POS empuja el catálogo a la tienda nueva por /api/sync/product")
    void theNewConnectionAcceptsTheCatalogRightAway() throws Exception {
        // El recorrido entero del alta: lo único que el POS hace después es empujar sus productos con el token que
        // generó, por el camino de siempre.
        Alta alta = Alta.nueva();
        mockMvc.perform(onboard(alta.body())).andExpect(status().isOk());
        long externalId = nextSeq();

        mockMvc.perform(post("/api/sync/product")
                        .header("Authorization", "Bearer " + alta.apiKey())
                        .header("X-Company-ID", String.valueOf(alta.companyId()))
                        .contentType("application/json")
                        .content("""
                                {"companyId":%d,"externalId":%d,"sku":"POS6-%d","name":"Cafe en grano","version":1,
                                 "price":8990,"stock":12,"categoryName":"Café"}"""
                                .formatted(alta.companyId(), externalId, externalId)))
                .andExpect(status().isOk());

        Store store = storeRepository.findBySlug(alta.slug()).orElseThrow();
        assertThat(productRepository.findByStoreIdAndSlug(store.getId(), "cafe-en-grano"))
                .hasValueSatisfying((Product product) -> assertThat(product.getStock()).isEqualTo(12));
    }

    private org.springframework.test.web.servlet.RequestBuilder onboard(String body) {
        return post("/api/platform/stores")
                .header("X-Platform-Key", platformKey)
                .contentType("application/json")
                .content(body);
    }

    /** Un alta desde el POS, con valores únicos por test. */
    private record Alta(String slug, String email, long companyId, String apiUrl, String apiKey, String webhookSecret) {

        static Alta nueva() {
            long seq = nextSeq();
            long companyId = 880_000_000L + seq;
            return new Alta("pos6-" + seq, "dueno-" + seq + "@test.local", companyId, "http://pos.internal/api",
                    "uvp_" + companyId + "_" + "a".repeat(40), "s".repeat(40));
        }

        Alta withSecrets(String newApiKey, String newWebhookSecret) {
            return new Alta(slug, email, companyId, apiUrl, newApiKey, newWebhookSecret);
        }

        Alta withEmail(String newEmail) {
            return new Alta(slug, newEmail, companyId, apiUrl, apiKey, webhookSecret);
        }

        Alta withSlug(String newSlug) {
            return new Alta(newSlug, email, companyId, apiUrl, apiKey, webhookSecret);
        }

        Alta withApiUrl(String newApiUrl) {
            return new Alta(slug, email, companyId, newApiUrl, apiKey, webhookSecret);
        }

        String body() {
            return """
                    {
                      "slug": "%s",
                      "domain": "",
                      "storeName": "Tienda del POS",
                      "adminName": "Dueño",
                      "adminEmail": "%s",
                      "pos": {
                        "companyId": %d,
                        "companyName": "Empresa del POS",
                        "apiUrl": "%s",
                        "apiKey": "%s",
                        "webhookSecret": "%s"
                      }
                    }""".formatted(slug, email, companyId, apiUrl, apiKey, webhookSecret);
        }
    }
}
