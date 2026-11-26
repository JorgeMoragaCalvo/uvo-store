package org.uvo.uvostore.multitenancy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.uvo.uvostore.entity.catalog.Category;
import org.uvo.uvostore.entity.catalog.Product;
import org.uvo.uvostore.entity.pos.PosConnection;
import org.uvo.uvostore.entity.pos.ProductSyncMapping;
import org.uvo.uvostore.entity.pos.enums.SyncDirection;
import org.uvo.uvostore.entity.pos.enums.SyncStatus;
import org.uvo.uvostore.entity.tenant.Store;
import org.uvo.uvostore.repository.PosConnectionKeyRepository;
import org.uvo.uvostore.repository.PosConnectionRepository;
import org.uvo.uvostore.repository.ProductSyncMappingRepository;
import org.uvo.uvostore.support.IntegrationTestSupport;
import org.uvo.uvostore.support.PosSigning;
import org.uvo.uvostore.support.WithPosIntegration;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * F01. La credencial POS de una tienda no puede escribir en el catálogo de otra.
 *
 * <p>El agujero que esto cierra: los filtros autenticaban la conexión de la cabecera
 * {@code X-Company-ID} —y validaban la firma con el secreto de <i>esa</i> conexión— pero los
 * controladores pasaban a los servicios el {@code companyId} del <b>cuerpo</b>. Como cada comerciante
 * configura sus propias credenciales POS desde Configuración &gt; General (el companyId sale del token
 * {@code uvp_<id>_…} que él mismo escribe), no hacía falta robar nada: A firmaba con lo suyo y
 * declaraba a B en el JSON.
 *
 * <p>Los dos casos no son igual de graves y por eso están los dos. El webhook necesita que exista un
 * mapping de B con ese {@code external_id} —enumerable, pero una condición al fin y al cabo—;
 * {@code /api/sync/product} <b>no necesita nada</b>: crea el producto en la tienda que diga el cuerpo.
 *
 * <p>Se comprueba contra la base, no contra el código de estado: un 403 con el producto igualmente
 * escrito sería un test verde sobre un sistema roto.
 */
@WithPosIntegration
class PosCrossTenantWriteTest extends IntegrationTestSupport {

    private static final String INJECTED_NAME = "Producto inyectado";
    // PosSyncServiceImpl.slugify() del nombre de arriba — es por donde se comprueba en la base si el
    // producto llegó a crearse, sin depender del código de estado de la respuesta.
    private static final String INJECTED_SLUG = "producto-inyectado";

    @Autowired
    private PosConnectionRepository posConnectionRepository;
    @Autowired
    private ProductSyncMappingRepository mappingRepository;
    @Autowired
    private PosConnectionKeyRepository keyRepository;

    @Test
    @DisplayName("Un webhook firmado por A que declara el companyId de B no toca el stock de B")
    void aWebhookSignedByAWithTheCompanyIdOfBDoesNotTouchB() throws Exception {
        PosConnection connectionA = createConnection(createStore("pos-cross-a"));
        PosConnection connectionB = createConnection(createStore("pos-cross-b"));

        Store storeB = connectionB.getStore();
        Product productB = createProduct(storeB, createCategory(storeB, "Ropa B"), "Producto de B", BigDecimal.valueOf(2000));
        long externalId = nextSeq();
        createMapping(connectionB, productB, externalId);
        int stockBefore = productB.getStock();

        // A firma con SU llave y manda SU cabecera: el filtro lo acepta, como debe.
        mockMvc.perform(PosSigning.signedPost("/api/webhooks/pos/stock-updated",
                        stockPayload(connectionB.getCompanyId(), externalId), connectionA))
                .andExpect(status().isForbidden());

        assertThat(productRepository.findById(productB.getId()).orElseThrow().getStock())
                .as("el stock de B no puede haberse movido")
                .isEqualTo(stockBefore);
    }

    @Test
    @DisplayName("La llave de A no crea productos en el catálogo de B")
    void theApiKeyOfADoesNotCreateProductsInB() throws Exception {
        PosConnection connectionA = createConnection(createStore("pos-sync-a"));
        PosConnection connectionB = createConnection(createStore("pos-sync-b"));

        mockMvc.perform(PosSigning.signedPost("/api/sync/product", syncPayload(connectionB.getCompanyId()),
                        connectionA))
                .andExpect(status().isForbidden());

        assertThat(productRepository.findByStoreIdAndSlug(connectionB.getStore().getId(), INJECTED_SLUG))
                .as("no puede aparecer el producto inyectado en el catálogo de B")
                .isEmpty();
    }

    @Test
    @DisplayName("Con su propio companyId, A sigue sincronizando en su propia tienda")
    void aStoreSyncingItsOwnCatalogueStillWorks() throws Exception {
        PosConnection connectionA = createConnection(createStore("pos-own-a"));

        // El control positivo: la corrección tiene que rechazar el cruce, no la sincronización.
        mockMvc.perform(PosSigning.signedPost("/api/sync/product", syncPayload(connectionA.getCompanyId()),
                        connectionA))
                .andExpect(status().isOk());

        assertThat(productRepository.findByStoreIdAndSlug(connectionA.getStore().getId(), INJECTED_SLUG))
                .as("en su propia tienda sí se crea")
                .isPresent();
    }

    // --- fixtures ----------------------------------------------------------------------------------

    private PosConnection createConnection(Store store) {
        PosConnection connection = new PosConnection();
        connection.setStore(store);
        connection.setCompanyName("Empresa POS " + store.getSlug());
        connection.setCompanyId(nextSeq());
        connection.setApiUrl("https://pos.test.local/api");
        connection.setActive(true);
        return PosSigning.installKeys(keyRepository, posConnectionRepository.save(connection));
    }

    private void createMapping(PosConnection connection, Product product, long externalId) {
        ProductSyncMapping mapping = new ProductSyncMapping();
        mapping.setProduct(product);
        mapping.setExternalId(externalId);
        mapping.setExternalSku(product.getSku());
        mapping.setCompanyId(connection.getCompanyId());
        mapping.setSyncStatus(SyncStatus.ACTIVE);
        mapping.setSyncDirection(SyncDirection.FROM_POS);
        mapping.setSyncStock(true);
        mapping.setSyncPrice(true);
        mapping.setSyncName(false);
        mapping.setSyncDescription(false);
        mapping.setLastSyncedAt(Instant.now());
        mappingRepository.save(mapping);
    }

    private String stockPayload(long companyId, long externalId) {
        return """
                {"event":"stock.updated","productId":%d,"sku":"SKU-1","companyId":%d,\
                "warehouseId":1,"version":2,"oldStock":10,"newStock":0,"stockWeb":0}"""
                .formatted(externalId, companyId);
    }

    private String syncPayload(long companyId) {
        return """
                {"companyId":%d,"externalId":%d,"sku":"POS-SKU-%d","name":"%s",\
                "price":1990,"stock":5,"active":true,"version":1}"""
                .formatted(companyId, nextSeq(), nextSeq(), INJECTED_NAME);
    }
}
