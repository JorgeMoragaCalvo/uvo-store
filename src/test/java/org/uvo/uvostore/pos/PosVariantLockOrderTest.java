package org.uvo.uvostore.pos;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.uvo.uvostore.entity.catalog.Product;
import org.uvo.uvostore.entity.catalog.ProductVariation;
import org.uvo.uvostore.entity.order.Order;
import org.uvo.uvostore.entity.order.OrderItem;
import org.uvo.uvostore.entity.order.enums.FulfillmentStatus;
import org.uvo.uvostore.entity.order.enums.OrderStatus;
import org.uvo.uvostore.entity.order.enums.PaymentStatus;
import org.uvo.uvostore.entity.pos.PosConnection;
import org.uvo.uvostore.entity.tenant.Store;
import org.uvo.uvostore.entity.tenant.enums.StoreStatus;
import org.uvo.uvostore.repository.OrderRepository;
import org.uvo.uvostore.repository.PosConnectionRepository;
import org.uvo.uvostore.repository.ProductRepository;
import org.uvo.uvostore.repository.ProductSyncMappingRepository;
import org.uvo.uvostore.repository.ProductVariationSyncMappingRepository;
import org.uvo.uvostore.repository.StoreRepository;
import org.uvo.uvostore.service.order.OrderInventoryService;
import org.uvo.uvostore.service.pos.PosSyncService;
import org.uvo.uvostore.service.pos.PosVariantFields;
import org.uvo.uvostore.service.pos.PosWebhookService;
import org.uvo.uvostore.service.pos.ProductUpdatePayload;
import org.uvo.uvostore.service.pos.SyncProductCommand;
import org.uvo.uvostore.support.WithPosIntegration;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * POS-5. Todo lo que escribe una variación bloquea antes a su producto padre, y por eso una venta y una
 * actualización de catálogo del mismo producto no pueden quedar esperándose la una a la otra.
 *
 * <p>El escritor de catálogo bloquea el padre y después escribe sus variaciones (el precio de la talla). La venta
 * hacía lo contrario: descontaba la variación y después recalculaba el padre. Con los dos a la vez, la venta tenía la
 * variación y esperaba el padre, y el escritor tenía el padre y esperaba la variación: Postgres abortaba uno.
 *
 * <p>Transacciones confirmadas de verdad y dos hilos, como {@link PosCatalogRaceTest}: dentro de la transacción de
 * un test no habría nadie más con quien chocar. La transacción A hace lo que hace el escritor en ese orden —toma el
 * padre, deja pasar a la venta y escribe la variación—, y la venta es la de verdad
 * ({@code OrderInventoryService.recoverMissedStock}, el mismo descuento que tras el pago).
 */
@WithPosIntegration
class PosVariantLockOrderTest {

    @Autowired
    private PosSyncService posSyncService;
    @Autowired
    private PosWebhookService posWebhookService;
    @Autowired
    private OrderInventoryService inventory;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private StoreRepository storeRepository;
    @Autowired
    private PosConnectionRepository connectionRepository;
    @Autowired
    private ProductRepository productRepository;
    @Autowired
    private ProductSyncMappingRepository mappingRepository;
    @Autowired
    private ProductVariationSyncMappingRepository variantMappingRepository;
    @Autowired
    private OrderRepository orderRepository;
    @Autowired
    private JdbcTemplate jdbc;

    private Store store;
    private PosConnection connection;
    private long externalId;
    private long m;
    private long l;
    private Long productId;
    private Long orderId;

    @BeforeEach
    void setUp() {
        long seq = System.nanoTime();
        store = storeRepository.save(Store.builder()
                .name("POS Variantes").slug("pos5-race-" + seq).status(StoreStatus.ACTIVE).build());

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
        m = seq + 1;
        l = seq + 2;
        productId = posSyncService.syncProduct(connection, new SyncProductCommand(externalId, "POL-" + seq,
                "Polera " + seq, null, null, null, null, null, true, 1,
                List.of(variant(m, 9990, 4, "M"), variant(l, 10990, 2, "L")))).productId();

        // Una venta de una M, cobrada, cuyo descuento todavía no corrió: lo que hace recoverMissedStock.
        orderId = new TransactionTemplate(transactionManager).execute(status -> paidOrderOfOneM(seq).getId());
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM orders WHERE store_id = ?", store.getId());
        jdbc.update("DELETE FROM sync_webhook_logs WHERE company_id = ?", connection.getCompanyId());
        jdbc.update("DELETE FROM product_sync_mapping WHERE company_id = ?", connection.getCompanyId());
        jdbc.update("DELETE FROM products WHERE store_id = ?", store.getId());
        jdbc.update("DELETE FROM attribute_values WHERE store_id = ?", store.getId());
        jdbc.update("DELETE FROM attributes WHERE store_id = ?", store.getId());
        jdbc.update("DELETE FROM pos_connections WHERE id = ?", connection.getId());
        jdbc.update("DELETE FROM stores WHERE id = ?", store.getId());
    }

    @Test
    @DisplayName("Una venta de una talla y un cambio de catálogo del mismo producto terminan los dos")
    void aVariantSaleAndACatalogWriteDoNotDeadlock() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            AtomicReference<Future<?>> sale = new AtomicReference<>();
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                // A, como el escritor: primero el padre.
                productRepository.findByIdForUpdate(productId);

                // B, la venta de la M, en otro hilo y con su propia transacción.
                sale.set(executor.submit(() -> inventory.recoverMissedStock(orderId)));
                assertThat(finishesWithin(sale.get(), Duration.ofSeconds(1)))
                        .as("la venta espera a que A suelte el producto")
                        .isFalse();

                // A, después, la variación: el precio de la M. Si la venta ya la tuviera bloqueada (porque la
                // tomó antes que el padre), aquí las dos se esperarían y Postgres abortaría una.
                posWebhookService.handleProductUpdated(new ProductUpdatePayload(connection.getCompanyId(), externalId,
                        "POL", 2, Map.of("variants", List.of(json(variant(m, 8990, null, "M")),
                                json(variant(l, 10990, null, "L"))))));
            });
            sale.get().get(10, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        ProductVariation medium = variantMappingRepository.findByMappingIdAndExternalVariantId(
                mappingRepository.findByExternalIdAndCompanyId(externalId, connection.getCompanyId())
                        .orElseThrow().getId(), m).orElseThrow().getVariation();
        assertThat(medium.getPrice()).as("el cambio de catálogo se aplicó").isEqualByComparingTo("8990");
        assertThat(medium.getStock()).as("y la venta también").isEqualTo(3);
        assertThat(productRepository.findById(productId).orElseThrow().getStock()).isEqualTo(3 + 2);
        assertThat(orderRepository.findById(orderId).orElseThrow().isStockApplied()).isTrue();
    }

    private Order paidOrderOfOneM(long seq) {
        Product product = productRepository.findById(productId).orElseThrow();
        ProductVariation medium = variantMappingRepository.findByMappingIdAndExternalVariantId(
                mappingRepository.findByExternalIdAndCompanyId(externalId, connection.getCompanyId())
                        .orElseThrow().getId(), m).orElseThrow().getVariation();

        Order order = new Order();
        order.setStore(product.getStore());
        order.setOrderNumber("ORD-POS5-RACE-" + seq);
        order.setCustomerEmail("comprador@test.local");
        order.setCustomerFirstName("Test");
        order.setCustomerLastName("Comprador");
        order.setSubtotal(medium.getPrice());
        order.setDiscountAmount(BigDecimal.ZERO);
        order.setShippingCost(BigDecimal.ZERO);
        order.setTaxAmount(BigDecimal.ZERO);
        order.setTotal(medium.getPrice());
        order.setStatus(OrderStatus.PROCESSING);
        order.setPaymentStatus(PaymentStatus.PAID);
        order.setFulfillmentStatus(FulfillmentStatus.UNFULFILLED);
        order.setStockApplied(false);

        OrderItem item = new OrderItem();
        item.setOrder(order);
        item.setProduct(product);
        item.setVariation(medium);
        item.setProductName(product.getName());
        item.setProductSku(medium.getSku());
        item.setQuantity(1);
        item.setPrice(medium.getPrice());
        item.setSubtotal(medium.getPrice());
        item.setTaxAmount(BigDecimal.ZERO);
        order.setItems(new ArrayList<>(List.of(item)));
        return orderRepository.save(order);
    }

    private static PosVariantFields variant(long id, long price, Integer stock, String size) {
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put("Talla", size);
        return new PosVariantFields(id, "POL-" + size + "-" + id, BigDecimal.valueOf(price), stock, attributes);
    }

    /** Como llega en {@code product.updated}: un mapa en camelCase. */
    private static Map<String, Object> json(PosVariantFields variant) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("variantId", variant.variantId());
        entry.put("sku", variant.sku());
        entry.put("price", variant.price());
        entry.put("attributes", variant.attributes());
        return entry;
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
}
