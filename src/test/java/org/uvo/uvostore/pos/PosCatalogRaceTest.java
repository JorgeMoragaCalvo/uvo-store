package org.uvo.uvostore.pos;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.uvo.uvostore.entity.catalog.Category;
import org.uvo.uvostore.entity.catalog.Product;
import org.uvo.uvostore.entity.catalog.enums.ProductType;
import org.uvo.uvostore.entity.pos.PosConnection;
import org.uvo.uvostore.entity.pos.ProductSyncMapping;
import org.uvo.uvostore.entity.pos.SyncWebhookLog;
import org.uvo.uvostore.entity.pos.enums.SyncDirection;
import org.uvo.uvostore.entity.pos.enums.SyncStatus;
import org.uvo.uvostore.entity.pos.enums.WebhookStatus;
import org.uvo.uvostore.entity.tenant.Store;
import org.uvo.uvostore.entity.tenant.enums.StoreStatus;
import org.uvo.uvostore.repository.CategoryRepository;
import org.uvo.uvostore.repository.PosConnectionRepository;
import org.uvo.uvostore.repository.ProductRepository;
import org.uvo.uvostore.repository.ProductSyncMappingRepository;
import org.uvo.uvostore.repository.StoreRepository;
import org.uvo.uvostore.repository.SyncWebHookLogRepository;
import org.uvo.uvostore.service.pos.PosWebhookService;
import org.uvo.uvostore.service.pos.ProductUpdatePayload;
import org.uvo.uvostore.service.pos.StockUpdatePayload;
import org.uvo.uvostore.support.WithPosIntegration;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * POS-3. Una entrada del POS no puede deshacer lo que otra transacción confirmó mientras tanto.
 *
 * <p>Tres de las carreras siguen el mismo guion, de un solo hilo y determinista. La transacción A carga el
 * mapeo y el producto, como hace el servicio al empezar. Una transacción B ({@code REQUIRES_NEW}) confirma un
 * cambio. Después A sigue con lo que tiene en memoria. Sin el {@code refresh}, A escribiría encima de B con lo
 * que leyó antes. La cuarta necesita un segundo hilo: es la que prueba el cerrojo.
 *
 * <p>Transacciones confirmadas de verdad, como {@code ConcurrentPaymentConfirmationTest}: dentro de la
 * transacción de un test, B no vería los datos de prueba y no habría nada que confirmar.
 */
@WithPosIntegration
class PosCatalogRaceTest {

    @Autowired
    private PosWebhookService posWebhookService;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private StoreRepository storeRepository;
    @Autowired
    private CategoryRepository categoryRepository;
    @Autowired
    private ProductRepository productRepository;
    @Autowired
    private PosConnectionRepository connectionRepository;
    @Autowired
    private ProductSyncMappingRepository mappingRepository;
    @Autowired
    private SyncWebHookLogRepository webhookLogRepository;

    private TransactionTemplate transactionA;
    private TransactionTemplate transactionB;

    private Store store;
    private Category category;
    private Product product;
    private PosConnection connection;
    private ProductSyncMapping mapping;
    private long externalId;

    @BeforeEach
    void setUp() {
        transactionA = new TransactionTemplate(transactionManager);
        transactionB = new TransactionTemplate(transactionManager);
        transactionB.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        long seq = System.nanoTime();
        store = storeRepository.save(Store.builder()
                .name("POS Race").slug("pos-race-" + seq).status(StoreStatus.ACTIVE).build());
        category = categoryRepository.save(Category.builder()
                .store(store).name("Carrera " + seq).slug("pos-race-cat-" + seq).active(true).build());
        product = productRepository.save(Product.builder()
                .store(store).category(category)
                .name("Artículo " + seq).slug("pos-race-prod-" + seq).sku("POSRACE-" + seq)
                .productType(ProductType.SIMPLE)
                .price(BigDecimal.valueOf(9990))
                .stock(10).manageStock(true).active(true)
                .build());

        connection = new PosConnection();
        connection.setStore(store);
        connection.setCompanyName("Empresa POS");
        connection.setCompanyId(seq);
        connection.setApiUrl("https://pos.test.local/api");
        connection.setApiKey("k");
        connection.setWebhookSecret("s");
        connection.setActive(true);
        connection = connectionRepository.save(connection);

        externalId = seq;
        mapping = new ProductSyncMapping();
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
        mapping.setCatalogVersion(10L);
        mapping.setLastSyncedAt(Instant.now());
        mapping = mappingRepository.save(mapping);
    }

    @AfterEach
    void cleanUp() {
        webhookLogRepository.deleteAll(
                webhookLogRepository.findTop50ByCompanyIdOrderByCreatedAtDesc(connection.getCompanyId()));
        mappingRepository.deleteById(mapping.getId());
        connectionRepository.delete(connection);
        productRepository.delete(product);
        categoryRepository.delete(category);
        storeRepository.delete(store);
    }

    @Test
    @DisplayName("Un cambio de precio del POS no deshace una venta web confirmada mientras tanto")
    void aCatalogEventDoesNotRevertAWebSale() {
        transactionA.executeWithoutResult(status -> {
            loadIntoTheSession();

            // B: una venta web confirma después de que A leyera el stock (10).
            transactionB.executeWithoutResult(s -> productRepository.decrementStock(product.getId(), 1));

            // A: el UPDATE del producto escribe todas las columnas. Sin el cerrojo y el refresh, también el
            // stock 10 que leyó al cargarlo, y la unidad vendida volvería a estar a la venta.
            posWebhookService.handleProductUpdated(productUpdated(11, 12990));
        });

        Product after = productRepository.findById(product.getId()).orElseThrow();
        assertThat(after.getStock()).as("la venta web sobrevive").isEqualTo(9);
        assertThat(after.getPrice()).isEqualByComparingTo("12990");
    }

    @Test
    @DisplayName("Una venta web que llega mientras se escribe el catálogo espera a que termine")
    void aWebSaleDuringACatalogWriteWaitsForIt() throws Exception {
        // El caso que el refresh solo no cubre: la venta confirma DESPUÉS de que A releyera el producto y antes
        // de que A confirme. Solo el cerrojo la hace esperar; sin él, A confirmaría encima el stock 10 que leyó.
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            AtomicReference<Future<?>> sale = new AtomicReference<>();
            transactionA.executeWithoutResult(status -> {
                posWebhookService.handleProductUpdated(productUpdated(11, 12990));

                // B, en otro hilo y con su propia transacción, mientras A sigue sin confirmar.
                sale.set(executor.submit(() -> transactionB.executeWithoutResult(
                        s -> productRepository.decrementStock(product.getId(), 1))));
                assertThat(finishesWithin(sale.get(), Duration.ofSeconds(1)))
                        .as("la venta espera a que se libere el producto")
                        .isFalse();
            });
            sale.get().get(10, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        Product after = productRepository.findById(product.getId()).orElseThrow();
        assertThat(after.getStock()).as("la venta se aplica sobre lo que dejó A").isEqualTo(9);
        assertThat(after.getPrice()).isEqualByComparingTo("12990");
    }

    @Test
    @DisplayName("Un evento cargado antes de que otro más nuevo confirmara se descarta igual")
    void anOlderEventLoadedBeforeANewerOneCommittedIsDiscarded() {
        AtomicReference<SyncWebhookLog> older = new AtomicReference<>();
        transactionA.executeWithoutResult(status -> {
            loadIntoTheSession();

            // B: la versión 12 llega y confirma mientras A, con la 11, todavía no ha escrito.
            transactionB.executeWithoutResult(s -> posWebhookService.handleProductUpdated(productUpdated(12, 15000)));

            // A tiene en memoria el mapeo con la versión 10. Sin releerlo con el cerrojo tomado, la 11 le
            // parecería nueva y pisaría el precio de la 12.
            older.set(posWebhookService.handleProductUpdated(productUpdated(11, 11000)));
        });

        assertThat(older.get().getStatus()).isEqualTo(WebhookStatus.DISCARDED);
        assertThat(productRepository.findById(product.getId()).orElseThrow().getPrice()).isEqualByComparingTo("15000");
        assertThat(mappingRepository.findById(mapping.getId()).orElseThrow().getCatalogVersion()).isEqualTo(12L);
    }

    @Test
    @DisplayName("Un cambio de stock no devuelve la versión de catálogo a la que leyó")
    void aStockEventDoesNotRevertTheCatalogVersion() {
        AtomicReference<SyncWebhookLog> stock = new AtomicReference<>();
        transactionA.executeWithoutResult(status -> {
            loadIntoTheSession();

            // B: la versión 12 confirma.
            transactionB.executeWithoutResult(s -> posWebhookService.handleProductUpdated(productUpdated(12, 15000)));

            // A: un stock.updated con el mapeo de la versión 10 en memoria. Un save() del mapeo copiaría todas
            // sus columnas, y la versión volvería a 10: la 11 se aceptaría después.
            stock.set(posWebhookService.handleStockUpdated(new StockUpdatePayload(
                    connection.getCompanyId(), externalId, "SKU", 10, 7, 7)));
        });

        assertThat(stock.get().getStatus()).isEqualTo(WebhookStatus.SUCCESS);
        assertThat(productRepository.findById(product.getId()).orElseThrow().getStock()).isEqualTo(7);
        assertThat(mappingRepository.findById(mapping.getId()).orElseThrow().getCatalogVersion()).isEqualTo(12L);
    }

    /** Lo que hace el servicio al empezar: el mapeo y el producto entran en la sesión de A. */
    private void loadIntoTheSession() {
        ProductSyncMapping loaded = mappingRepository.findByExternalIdAndCompanyId(externalId, connection.getCompanyId())
                .orElseThrow();
        assertThat(loaded.getCatalogVersion()).isEqualTo(10L);
        assertThat(productRepository.findById(product.getId()).orElseThrow().getStock()).isEqualTo(10);
    }

    private static boolean finishesWithin(Future<?> future, Duration timeout) {
        try {
            future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            return true;
        } catch (TimeoutException e) {
            return false;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private ProductUpdatePayload productUpdated(long version, long price) {
        return new ProductUpdatePayload(connection.getCompanyId(), externalId, "SKU", version, Map.of("price", price));
    }
}
