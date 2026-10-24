package org.uvo.uvostore.pos;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.uvo.uvostore.entity.catalog.Product;
import org.uvo.uvostore.entity.pos.PosConnection;
import org.uvo.uvostore.entity.pos.ProductSyncMapping;
import org.uvo.uvostore.entity.tenant.Store;
import org.uvo.uvostore.repository.PosConnectionRepository;
import org.uvo.uvostore.repository.ProductSyncMappingRepository;
import org.uvo.uvostore.service.pos.PosSyncService;
import org.uvo.uvostore.service.pos.PosVariantFields;
import org.uvo.uvostore.service.pos.SyncProductCommand;
import org.uvo.uvostore.support.IntegrationTestSupport;
import org.uvo.uvostore.support.WithPosIntegration;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * POS-6. La carga inicial del catálogo la empuja el POS después del alta, un {@code POST /api/sync/product} por
 * producto. Con un catálogo entero aparecen cosas que de a uno no se veían: dos productos con el mismo nombre, dos
 * categorías que solo cambian en mayúsculas, un SKU repetido. Antes cada una era un 500 por una restricción única de
 * la base, y el POS la reintentaría sin fin.
 */
@WithPosIntegration
class PosBulkCatalogPushTest extends IntegrationTestSupport {

    private static final String API_KEY = "clave-pos6-carga";

    @Autowired
    private PosConnectionRepository connectionRepository;
    @Autowired
    private ProductSyncMappingRepository mappingRepository;
    @Autowired
    private PosSyncService posSyncService;

    private Store store;
    private PosConnection connection;

    @BeforeEach
    void posStore() {
        store = createStore("pos6-carga");
        setSetting(store, "pos_sync_enabled", "true");
        connection = new PosConnection();
        connection.setStore(store);
        connection.setCompanyName("Empresa POS");
        connection.setCompanyId(nextSeq());
        connection.setApiUrl("https://pos.test.local/api");
        connection.setApiKey(API_KEY);
        connection.setWebhookSecret("s");
        connection.setActive(true);
        connectionRepository.save(connection);
    }

    @Test
    @DisplayName("Dos productos con el mismo nombre se crean los dos, con slugs distintos")
    void twoProductsWithTheSameNameGetDistinctSlugs() throws Exception {
        long first = push("Polera", "POL-A-" + nextSeq(), null);
        long second = push("Polera", "POL-B-" + nextSeq(), null);
        long third = push("Polera", "POL-C-" + nextSeq(), null);

        assertThat(productRepository.findById(first)).hasValueSatisfying(p -> assertThat(p.getSlug()).isEqualTo("polera"));
        assertThat(productRepository.findById(second)).hasValueSatisfying(p -> assertThat(p.getSlug()).isEqualTo("polera-2"));
        assertThat(productRepository.findById(third)).hasValueSatisfying(p -> assertThat(p.getSlug()).isEqualTo("polera-3"));
    }

    @Test
    @DisplayName("Un nombre que no deja nada para el slug da 'producto'")
    void aNameWithoutLatinCharactersGetsAGenericSlug() throws Exception {
        long id = push("¡¡!!", "SIMB-" + nextSeq(), null);

        assertThat(productRepository.findById(id)).hasValueSatisfying(p -> assertThat(p.getSlug()).isEqualTo("producto"));
    }

    @Test
    @DisplayName("Un renombre hacia un nombre ocupado toma un slug libre, y sin cambio de nombre el slug se queda")
    void aRenameToATakenNameGetsAFreeSlug() throws Exception {
        long mesa = push("Mesa", "MESA-" + nextSeq(), null);
        String sillaSku = "SILLA-" + nextSeq();
        long sillaExternalId = nextSeq();
        long silla = posSyncService.syncProduct(connection, command(sillaExternalId, sillaSku, "Silla", 1, null))
                .productId();
        ProductSyncMapping sillaMapping = mappingRepository
                .findByExternalIdAndCompanyId(sillaExternalId, connection.getCompanyId()).orElseThrow();
        sillaMapping.setSyncName(true);
        mappingRepository.save(sillaMapping);

        // El POS renombra la silla a "Mesa": el nombre es del POS, el slug "mesa" ya es de otro producto.
        posSyncService.syncProduct(connection, command(sillaExternalId, sillaSku, "Mesa", 2, null));

        Product renamed = productRepository.findById(silla).orElseThrow();
        assertThat(renamed.getName()).isEqualTo("Mesa");
        assertThat(renamed.getSlug()).isEqualTo("mesa-2");
        assertThat(productRepository.findById(mesa)).hasValueSatisfying(p -> assertThat(p.getSlug()).isEqualTo("mesa"));

        // Una entrada más nueva con el mismo nombre no le cambia el slug: las URL de la tienda no se mueven solas.
        posSyncService.syncProduct(connection, command(sillaExternalId, sillaSku, "Mesa", 3, null));
        assertThat(productRepository.findById(silla)).hasValueSatisfying(p -> assertThat(p.getSlug()).isEqualTo("mesa-2"));

        // Un nombre distinto que da el mismo slug: el que ya tiene es suyo y sigue libre, así que lo conserva. Sin
        // excluir al propio producto pasaría a mesa-3 sin motivo.
        posSyncService.syncProduct(connection, command(sillaExternalId, sillaSku, "MESA", 4, null));
        assertThat(productRepository.findById(silla)).hasValueSatisfying(p -> assertThat(p.getSlug()).isEqualTo("mesa-2"));
    }

    @Test
    @DisplayName("Dos categorías que solo cambian en mayúsculas son la misma")
    void categoriesThatDifferOnlyInCaseAreTheSame() throws Exception {
        long first = push("Agua", "AGUA-" + nextSeq(), "Bebidas");
        long second = push("Jugo", "JUGO-" + nextSeq(), "bebidas");

        Long firstCategory = productRepository.findById(first).orElseThrow().getCategory().getId();
        Long secondCategory = productRepository.findById(second).orElseThrow().getCategory().getId();
        assertThat(secondCategory).isEqualTo(firstCategory);
    }

    @Test
    @DisplayName("Un SKU de producto que ya existe en la tienda es un 400 que lo nombra, y no crea nada")
    void aDuplicateProductSkuIsABadRequest() throws Exception {
        String sku = "DUP-" + nextSeq();
        push("Taza", sku, null);
        long externalId = nextSeq();

        mockMvc.perform(syncProduct("""
                        {"companyId":%d,"externalId":%d,"sku":"%s","name":"Otra taza","version":1,"price":1990,"stock":3}"""
                        .formatted(connection.getCompanyId(), externalId, sku)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString(sku)));

        assertThat(mappingRepository.findByExternalIdAndCompanyId(externalId, connection.getCompanyId())).isEmpty();
    }

    @Test
    @DisplayName("Un SKU de variante que ya existe en la tienda es un 400 que lo nombra")
    void aDuplicateVariantSkuIsABadRequest() throws Exception {
        String variantSku = "VAR-" + nextSeq();
        posSyncService.syncProduct(connection, command(nextSeq(), "PAD-A-" + nextSeq(), "Polerón", 1,
                List.of(new PosVariantFields(1L, variantSku, new BigDecimal("15990"), 2, Map.of("Talla", "M")))));
        long externalId = nextSeq();

        mockMvc.perform(syncProduct("""
                        {"companyId":%d,"externalId":%d,"sku":"PAD-B-%d","name":"Otro polerón","version":1,
                         "variants":[{"variantId":2,"sku":"%s","price":15990,"stock":1,"attributes":{"Talla":"L"}}]}"""
                        .formatted(connection.getCompanyId(), externalId, externalId, variantSku)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString(variantSku)));

        assertThat(mappingRepository.findByExternalIdAndCompanyId(externalId, connection.getCompanyId())).isEmpty();
    }

    /** Empuja un producto simple por el endpoint, como el POS en la carga inicial, y devuelve su id. */
    private long push(String name, String sku, String category) throws Exception {
        long externalId = nextSeq();
        String response = mockMvc.perform(syncProduct("""
                        {"companyId":%d,"externalId":%d,"sku":"%s","name":"%s","version":1,"price":1990,"stock":5%s}"""
                        .formatted(connection.getCompanyId(), externalId, sku, name,
                                category == null ? "" : ",\"categoryName\":\"" + category + "\"")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).get("product_id").asLong();
    }

    private org.springframework.test.web.servlet.RequestBuilder syncProduct(String body) {
        return post("/api/sync/product")
                .header("Authorization", "Bearer " + API_KEY)
                .header("X-Company-ID", String.valueOf(connection.getCompanyId()))
                .contentType("application/json")
                .content(body);
    }

    private static SyncProductCommand command(long externalId, String sku, String name, long version,
                                              List<PosVariantFields> variants) {
        return new SyncProductCommand(externalId, sku, name, null, variants == null ? new BigDecimal("1990") : null,
                variants == null ? 5 : null, null, null, true, version, variants);
    }
}
