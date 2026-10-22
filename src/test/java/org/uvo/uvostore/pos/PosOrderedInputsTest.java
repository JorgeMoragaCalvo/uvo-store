package org.uvo.uvostore.pos;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.uvo.uvostore.entity.catalog.Category;
import org.uvo.uvostore.entity.catalog.Product;
import org.uvo.uvostore.entity.pos.PosConnection;
import org.uvo.uvostore.entity.pos.ProductSyncMapping;
import org.uvo.uvostore.entity.pos.SyncWebhookLog;
import org.uvo.uvostore.entity.pos.enums.SyncDirection;
import org.uvo.uvostore.entity.pos.enums.SyncStatus;
import org.uvo.uvostore.entity.pos.enums.WebhookStatus;
import org.uvo.uvostore.entity.tenant.Store;
import org.uvo.uvostore.repository.PosConnectionRepository;
import org.uvo.uvostore.repository.ProductSyncMappingRepository;
import org.uvo.uvostore.repository.SyncWebHookLogRepository;
import org.uvo.uvostore.service.pos.PosSyncService;
import org.uvo.uvostore.service.pos.PosWebhookService;
import org.uvo.uvostore.service.pos.ProductUpdatePayload;
import org.uvo.uvostore.service.pos.StockUpdatePayload;
import org.uvo.uvostore.service.pos.SyncProductCommand;
import org.uvo.uvostore.service.pos.SyncProductResult;
import org.uvo.uvostore.support.IntegrationTestSupport;
import org.uvo.uvostore.support.WithPosIntegration;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * POS-3. Las dos entradas de catálogo del POS —la sincronización y el webhook {@code product.updated}— aplican
 * las mismas reglas: los flags del mapeo, la versión y el estado del mapeo.
 *
 * <p>Antes la sincronización pisaba en cada envío todo lo que traía (la descripción que escribió la web, el
 * stock, el estado, la categoría) y reactivaba un mapeo pausado, y ninguna de las dos miraba si lo que llegaba
 * era más viejo que lo aplicado. Las carreras entre transacciones confirmadas están en
 * {@link PosCatalogRaceTest}.
 */
@WithPosIntegration
class PosOrderedInputsTest extends IntegrationTestSupport {

    private static final String WEB_DESCRIPTION = "Descripción escrita en la web";

    @Autowired
    private PosSyncService posSyncService;
    @Autowired
    private PosWebhookService posWebhookService;
    @Autowired
    private PosConnectionRepository connectionRepository;
    @Autowired
    private ProductSyncMappingRepository mappingRepository;
    @Autowired
    private SyncWebHookLogRepository webhookLogRepository;

    // --- la sincronización respeta lo que es de la web ----------------------------------------------

    @Test
    @DisplayName("La sincronización ya no pisa lo que es de la web: con los flags por defecto solo cambia el precio")
    void syncLeavesWebOwnedFieldsAlone() {
        Fixture fixture = mapped(false, false);
        String nameBefore = fixture.product.getName();

        SyncProductResult result = posSyncService.syncProduct(fixture.connection, sync(fixture, 1,
                "Nombre del POS", "Descripción del POS", 12990, 99, "Categoría del POS", true));

        assertThat(result.applied()).isTrue();
        Product product = reload(fixture.product);
        assertThat(product.getPrice()).isEqualByComparingTo("12990");
        assertThat(product.getName()).isEqualTo(nameBefore);
        assertThat(product.getDescription())
                .as("la descripción que escribió la web sobrevive a la sincronización")
                .isEqualTo(WEB_DESCRIPTION);
        assertThat(product.getStock()).as("el stock solo entra por stock.updated").isEqualTo(10);
        assertThat(product.isActive()).as("el estado es de la web después de crear").isFalse();
        assertThat(product.getCategory().getId()).isEqualTo(fixture.category.getId());
        assertThat(categoryRepository.findByStoreIdAndName(fixture.store.getId(), "Categoría del POS"))
                .as("ni se crea una categoría que no se va a usar")
                .isEmpty();
    }

    @Test
    @DisplayName("El nombre y la descripción se sincronizan solo con su flag")
    void syncWritesNameAndDescriptionOnlyWhenFlagged() {
        Fixture fixture = mapped(true, true);

        posSyncService.syncProduct(fixture.connection, sync(fixture, 1,
                "Polera Azul", "Descripción del POS", 9990, 99, null, true));

        Product product = reload(fixture.product);
        assertThat(product.getName()).isEqualTo("Polera Azul");
        assertThat(product.getSlug()).isEqualTo("polera-azul");
        assertThat(product.getDescription()).isEqualTo("Descripción del POS");
        assertThat(product.getStock()).isEqualTo(10);
    }

    @Test
    @DisplayName("La sincronización no deshace una venta web: no escribe el stock de un producto que ya existe")
    void syncNeverWritesTheStockOfAnExistingProduct() {
        Fixture fixture = mapped(false, false);
        // Una venta web deja 9; el POS todavía no la conoce y sincroniza con su 10.
        productRepository.decrementStock(fixture.product.getId(), 1);

        posSyncService.syncProduct(fixture.connection, sync(fixture, 1, "x", null, 9990, 10, null, true));

        assertThat(reload(fixture.product).getStock()).isEqualTo(9);
    }

    @Test
    @DisplayName("Al crear, la sincronización sí lo fija todo y guarda la versión")
    void syncCreatesTheProductWithEverythingAndRecordsTheVersion() {
        Fixture fixture = mapped(false, false);
        long externalId = nextSeq();

        SyncProductResult result = posSyncService.syncProduct(fixture.connection, new SyncProductCommand(
                externalId, "NUEVO-" + externalId, "Gorro Nuevo", "Del POS", BigDecimal.valueOf(4990), 7, null,
                "Accesorios", false, 7, null));

        assertThat(result.created()).isTrue();
        assertThat(result.applied()).isTrue();
        Product product = productRepository.findById(result.productId()).orElseThrow();
        assertThat(product.getStock()).isEqualTo(7);
        assertThat(product.isActive()).isFalse();
        assertThat(product.getCategory().getName()).isEqualTo("Accesorios");
        assertThat(product.getDescription()).isEqualTo("Del POS");
        assertThat(mappingRepository.findByExternalIdAndCompanyId(externalId, fixture.connection.getCompanyId())
                .orElseThrow().getCatalogVersion()).isEqualTo(7L);
    }

    @Test
    @DisplayName("Un mapeo pausado no se escribe ni se reactiva desde la sincronización")
    void aPausedMappingIsNeitherWrittenNorReactivatedBySync() {
        Fixture fixture = mapped(false, false);
        fixture.mapping.setSyncStatus(SyncStatus.PAUSED);
        mappingRepository.save(fixture.mapping);

        SyncProductResult result = posSyncService.syncProduct(fixture.connection,
                sync(fixture, 1, "x", null, 1, 10, null, true));

        assertThat(result.applied()).isFalse();
        assertThat(reload(fixture.product).getPrice()).isEqualByComparingTo("9990");
        assertThat(reloadMapping(fixture).getSyncStatus()).isEqualTo(SyncStatus.PAUSED);
        assertThat(log(result.logId()).getStatus()).isEqualTo(WebhookStatus.FAILED);
    }

    // --- orden: lo viejo se descarta y queda registrado ---------------------------------------------

    @Test
    @DisplayName("Un product.updated más viejo que el aplicado se descarta y queda registrado")
    void anOlderProductUpdatedIsDiscardedAndLogged() {
        Fixture fixture = mapped(false, false);

        SyncWebhookLog newer = posWebhookService.handleProductUpdated(productUpdated(fixture, 12, 15000));
        SyncWebhookLog older = posWebhookService.handleProductUpdated(productUpdated(fixture, 11, 11000));

        assertThat(newer.getStatus()).isEqualTo(WebhookStatus.SUCCESS);
        assertThat(reload(fixture.product).getPrice())
                .as("el evento viejo que llega tarde no deshace el nuevo")
                .isEqualByComparingTo("15000");
        assertThat(older.getStatus()).isEqualTo(WebhookStatus.DISCARDED);
        assertThat(older.getErrorMessage()).contains("versión 11").contains("la 12");
        assertThat(reloadMapping(fixture).getCatalogVersion()).isEqualTo(12L);
    }

    @Test
    @DisplayName("La misma versión dos veces: la segunda se descarta como ya aplicada")
    void aRepeatedVersionIsDiscardedAsAlreadyApplied() {
        Fixture fixture = mapped(false, false);

        posWebhookService.handleProductUpdated(productUpdated(fixture, 12, 15000));
        SyncWebhookLog repeated = posWebhookService.handleProductUpdated(productUpdated(fixture, 12, 16000));

        assertThat(repeated.getStatus()).isEqualTo(WebhookStatus.DISCARDED);
        assertThat(repeated.getErrorMessage()).contains("ya estaba aplicada");
        assertThat(reload(fixture.product).getPrice()).isEqualByComparingTo("15000");
    }

    @Test
    @DisplayName("La sincronización y el webhook comparten la versión: una sincronización vieja también se descarta")
    void syncAndWebhookShareTheVersion() {
        Fixture fixture = mapped(false, false);

        posWebhookService.handleProductUpdated(productUpdated(fixture, 12, 15000));
        SyncProductResult result = posSyncService.syncProduct(fixture.connection,
                sync(fixture, 11, "x", null, 11000, 10, null, true));

        assertThat(result.applied()).isFalse();
        assertThat(result.message()).contains("versión 11");
        assertThat(reload(fixture.product).getPrice()).isEqualByComparingTo("15000");
        assertThat(log(result.logId()).getStatus()).isEqualTo(WebhookStatus.DISCARDED);
    }

    @Test
    @DisplayName("La versión avanza aunque el evento no traiga nada con flag")
    void theVersionAdvancesEvenWithNoFlaggedField() {
        Fixture fixture = mapped(false, false);

        SyncWebhookLog nameOnly = posWebhookService.handleProductUpdated(new ProductUpdatePayload(
                fixture.connection.getCompanyId(), fixture.externalId, "SKU", 5, Map.of("name", "Otro nombre")));
        SyncWebhookLog older = posWebhookService.handleProductUpdated(productUpdated(fixture, 4, 11000));

        assertThat(nameOnly.getStatus()).isEqualTo(WebhookStatus.SUCCESS);
        assertThat(reloadMapping(fixture).getCatalogVersion()).isEqualTo(5L);
        assertThat(older.getStatus()).isEqualTo(WebhookStatus.DISCARDED);
        assertThat(reload(fixture.product).getPrice()).isEqualByComparingTo("9990");
    }

    // --- stock: un evento repetido no es una divergencia --------------------------------------------

    @Test
    @DisplayName("Un stock.updated repetido se descarta sin alertar una divergencia que no existe")
    void aRepeatedStockEventIsDiscardedWithoutADivergence() {
        Fixture fixture = mapped(false, false);
        StockUpdatePayload event = new StockUpdatePayload(
                fixture.connection.getCompanyId(), fixture.externalId, null, "SKU", 10, 7, 7);

        SyncWebhookLog first = posWebhookService.handleStockUpdated(event);
        // El POS lo reintenta: es el mismo evento con otra firma, así que el filtro no lo para.
        SyncWebhookLog repeated = posWebhookService.handleStockUpdated(event);

        assertThat(first.getStatus()).isEqualTo(WebhookStatus.SUCCESS);
        assertThat(repeated.getStatus()).isEqualTo(WebhookStatus.DISCARDED);
        assertThat(repeated.getErrorMessage()).doesNotContain("Divergencia");
        assertThat(reload(fixture.product).getStock()).isEqualTo(7);
    }

    // --- registro -----------------------------------------------------------------------------------

    @Test
    @DisplayName("Cada sincronización deja su fila en el registro")
    void everySyncLeavesALogRow() {
        Fixture fixture = mapped(false, false);

        SyncProductResult result = posSyncService.syncProduct(fixture.connection,
                sync(fixture, 3, "x", null, 12990, 10, null, true));

        SyncWebhookLog log = log(result.logId());
        assertThat(log.getEventType()).isEqualTo("product.sync");
        assertThat(log.getStatus()).isEqualTo(WebhookStatus.SUCCESS);
        assertThat(log.getCompanyId()).isEqualTo(fixture.connection.getCompanyId());
        assertThat(log.getPayload()).containsEntry("version", 3L);
    }

    // --- fixtures ----------------------------------------------------------------------------------

    private record Fixture(Store store, Category category, Product product, PosConnection connection,
                           ProductSyncMapping mapping, long externalId) {
    }

    /** Un producto del POS al que la web ya le escribió una descripción y lo dejó inactivo. */
    private Fixture mapped(boolean syncName, boolean syncDescription) {
        Store store = createStore("pos3");
        Category category = createCategory(store, "Web");
        Product product = createProduct(store, category, "Polera", BigDecimal.valueOf(9990));
        product.setDescription(WEB_DESCRIPTION);
        product.setActive(false);
        productRepository.save(product);

        PosConnection connection = new PosConnection();
        connection.setStore(store);
        connection.setCompanyName("Empresa POS");
        connection.setCompanyId(nextSeq());
        connection.setApiUrl("https://pos.test.local/api");
        connection.setApiKey("k");
        connection.setWebhookSecret("s");
        connection.setActive(true);
        connectionRepository.save(connection);

        long externalId = nextSeq();
        ProductSyncMapping mapping = new ProductSyncMapping();
        mapping.setProduct(product);
        mapping.setExternalId(externalId);
        mapping.setExternalSku(product.getSku());
        mapping.setCompanyId(connection.getCompanyId());
        mapping.setSyncStatus(SyncStatus.ACTIVE);
        mapping.setSyncDirection(SyncDirection.FROM_POS);
        mapping.setSyncStock(true);
        mapping.setSyncPrice(true);
        mapping.setSyncName(syncName);
        mapping.setSyncDescription(syncDescription);
        mapping.setLastSyncedAt(Instant.now());
        mappingRepository.save(mapping);

        return new Fixture(store, category, product, connection, mapping, externalId);
    }

    private SyncProductCommand sync(Fixture fixture, long version, String name, String description, long price,
                                    int stock, String categoryName, boolean active) {
        return new SyncProductCommand(fixture.externalId, fixture.product.getSku(), name, description,
                BigDecimal.valueOf(price), stock, null, categoryName, active, version, null);
    }

    private ProductUpdatePayload productUpdated(Fixture fixture, long version, long price) {
        return new ProductUpdatePayload(fixture.connection.getCompanyId(), fixture.externalId, "SKU", version,
                Map.of("price", price));
    }

    private Product reload(Product product) {
        return productRepository.findById(product.getId()).orElseThrow();
    }

    private ProductSyncMapping reloadMapping(Fixture fixture) {
        return mappingRepository.findById(fixture.mapping.getId()).orElseThrow();
    }

    private SyncWebhookLog log(Long id) {
        return webhookLogRepository.findById(id).orElseThrow();
    }
}
