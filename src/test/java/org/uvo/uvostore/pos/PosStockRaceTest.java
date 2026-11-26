package org.uvo.uvostore.pos;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.uvo.uvostore.entity.catalog.Product;
import org.uvo.uvostore.entity.order.Order;
import org.uvo.uvostore.entity.order.OrderItem;
import org.uvo.uvostore.entity.order.enums.FulfillmentStatus;
import org.uvo.uvostore.entity.order.enums.OrderStatus;
import org.uvo.uvostore.entity.order.enums.PaymentStatus;
import org.uvo.uvostore.entity.outbox.OutboxMessage;
import org.uvo.uvostore.entity.outbox.enums.OutboxStatus;
import org.uvo.uvostore.entity.outbox.enums.OutboxType;
import org.uvo.uvostore.entity.pos.PosConnection;
import org.uvo.uvostore.entity.pos.ProductSyncMapping;
import org.uvo.uvostore.entity.pos.SyncWebhookLog;
import org.uvo.uvostore.entity.pos.enums.SyncDirection;
import org.uvo.uvostore.entity.pos.enums.SyncStatus;
import org.uvo.uvostore.entity.pos.enums.WebhookStatus;
import org.uvo.uvostore.entity.tenant.Store;
import org.uvo.uvostore.repository.OrderRepository;
import org.uvo.uvostore.repository.OutboxMessageRepository;
import org.uvo.uvostore.repository.PosConnectionRepository;
import org.uvo.uvostore.repository.ProductSyncMappingRepository;
import org.uvo.uvostore.service.pos.PosWebhookService;
import org.uvo.uvostore.service.pos.StockUpdatePayload;
import org.uvo.uvostore.support.IntegrationTestSupport;
import org.uvo.uvostore.support.WithPosIntegration;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F13 e IMP-08. El POS no puede borrar una venta web con su valor absoluto (contrato §6).
 *
 * <p>Una corrección de stock del POS no debe devolver unidades de ventas web cobradas que todavía no llegaron al POS.
 * Antes eso se protegía con el {@code oldStock} del aviso; con el contrato v1 el aviso trae {@code stock_web} y su
 * {@code version}, y la tienda salta el stock mientras el producto tenga una venta pendiente de registrar. Lo aplica
 * la conciliación cuando la venta ya está en el POS.
 */
@WithPosIntegration
class PosStockRaceTest extends IntegrationTestSupport {

    @Autowired
    private PosWebhookService posWebhookService;
    @Autowired
    private PosConnectionRepository connectionRepository;
    @Autowired
    private ProductSyncMappingRepository mappingRepository;
    @Autowired
    private OrderRepository orderRepository;
    @Autowired
    private OutboxMessageRepository outboxRepository;

    @Test
    @DisplayName("Una venta web que el POS todavía no registró no se borra con el absoluto del POS")
    void aSaleThePosHasNotRegisteredIsNotErased() {
        // El POS leyó 5; se vendió una unidad en la web (la tienda quedó en 4) y su venta sigue en el outbox. El aviso
        // dice "stock_web 5".
        Fixture fixture = syncedProduct(4);
        saleOf(fixture, OutboxStatus.PENDING);

        SyncWebhookLog log = posWebhookService.handleStockUpdated(stock(fixture, 2, 5));

        assertThat(reloadStock(fixture.product)).as("el absoluto del POS no resucita una unidad vendida").isEqualTo(4);
        assertThat(log.getStatus()).isEqualTo(WebhookStatus.SUCCESS);
        assertThat(log.getErrorMessage()).as("queda dicho por qué no se aplicó").contains("ventas web");
    }

    @Test
    @DisplayName("Una venta que no llegó al POS (fallida) también protege el stock")
    void aFailedSaleAlsoProtectsTheStock() {
        Fixture fixture = syncedProduct(4);
        saleOf(fixture, OutboxStatus.FAILED);

        posWebhookService.handleStockUpdated(stock(fixture, 2, 5));

        assertThat(reloadStock(fixture.product)).isEqualTo(4);
    }

    @Test
    @DisplayName("Con la venta ya registrada en el POS, el stock del POS entra")
    void onceTheSaleIsInThePosItsStockIsApplied() {
        Fixture fixture = syncedProduct(4);
        saleOf(fixture, OutboxStatus.SENT);

        SyncWebhookLog log = posWebhookService.handleStockUpdated(stock(fixture, 2, 4));
        posWebhookService.handleStockUpdated(stock(fixture, 3, 9));

        assertThat(log.getStatus()).isEqualTo(WebhookStatus.SUCCESS);
        assertThat(reloadStock(fixture.product)).isEqualTo(9);
    }

    @Test
    @DisplayName("Sin nada por medio, el stock del POS sí entra")
    void withoutAPendingSaleThePosValueIsApplied() {
        Fixture fixture = syncedProduct(5);

        SyncWebhookLog log = posWebhookService.handleStockUpdated(stock(fixture, 2, 9));

        assertThat(reloadStock(fixture.product)).isEqualTo(9);
        assertThat(log.getStatus()).isEqualTo(WebhookStatus.SUCCESS);
    }

    @Test
    @DisplayName("Un aviso repetido o más viejo se descarta y no pisa: lo ordena la versión")
    void aRepeatedOrOlderEventDoesNotOverwrite() {
        Fixture fixture = syncedProduct(5);
        posWebhookService.handleStockUpdated(stock(fixture, 3, 3));

        SyncWebhookLog repeated = posWebhookService.handleStockUpdated(stock(fixture, 3, 3));
        SyncWebhookLog older = posWebhookService.handleStockUpdated(stock(fixture, 2, 8));

        assertThat(reloadStock(fixture.product)).isEqualTo(3);
        assertThat(repeated.getStatus()).isEqualTo(WebhookStatus.DISCARDED);
        assertThat(older.getStatus()).isEqualTo(WebhookStatus.DISCARDED);
        assertThat(older.getErrorMessage()).doesNotContain("Divergencia");
    }

    // --- fixtures ----------------------------------------------------------------------------------

    private record Fixture(Product product, long companyId, long externalId, Store store) {
    }

    private StockUpdatePayload stock(Fixture fixture, long version, int stockWeb) {
        return new StockUpdatePayload(fixture.companyId, fixture.externalId, "SKU-1", version, null, null, stockWeb);
    }

    private Fixture syncedProduct(int stock) {
        Store store = createStore("pos-race");
        Product product = createProduct(store, createCategory(store, "Cat"), "Producto POS",
                BigDecimal.valueOf(9990));
        product.setStock(stock);
        productRepository.save(product);

        long companyId = nextSeq();
        PosConnection connection = new PosConnection();
        connection.setStore(store);
        connection.setCompanyName("Empresa POS");
        connection.setCompanyId(companyId);
        connection.setApiUrl("https://pos.test.local/api");
        connection.setActive(true);
        connectionRepository.save(connection);

        long externalId = nextSeq();
        ProductSyncMapping mapping = new ProductSyncMapping();
        mapping.setProduct(product);
        mapping.setExternalId(externalId);
        mapping.setExternalSku(product.getSku());
        mapping.setCompanyId(companyId);
        mapping.setSyncStatus(SyncStatus.ACTIVE);
        mapping.setSyncDirection(SyncDirection.FROM_POS);
        mapping.setSyncStock(true);
        mapping.setSyncPrice(true);
        mapping.setSyncName(false);
        mapping.setSyncDescription(false);
        mapping.setCatalogVersion(1L);
        mapping.setLastSyncedAt(Instant.now());
        mappingRepository.save(mapping);

        return new Fixture(product, companyId, externalId, store);
    }

    /** Una venta web de una unidad, cobrada y con el stock ya descontado, con su fila POS_ORDER en ese estado. */
    private void saleOf(Fixture fixture, OutboxStatus status) {
        long seq = nextSeq();
        Order order = new Order();
        order.setStore(fixture.store);
        order.setOrderNumber("ORD-RACE-" + seq);
        order.setCustomerEmail("comprador@test.local");
        order.setCustomerFirstName("Test");
        order.setCustomerLastName("Comprador");
        order.setSubtotal(fixture.product.getPrice());
        order.setDiscountAmount(BigDecimal.ZERO);
        order.setShippingCost(BigDecimal.ZERO);
        order.setTaxAmount(BigDecimal.ZERO);
        order.setTotal(fixture.product.getPrice());
        order.setStatus(OrderStatus.PROCESSING);
        order.setPaymentStatus(PaymentStatus.PAID);
        order.setFulfillmentStatus(FulfillmentStatus.UNFULFILLED);
        order.setStockApplied(true);
        OrderItem item = new OrderItem();
        item.setOrder(order);
        item.setProduct(fixture.product);
        item.setProductName(fixture.product.getName());
        item.setProductSku(fixture.product.getSku());
        item.setQuantity(1);
        item.setPrice(fixture.product.getPrice());
        item.setSubtotal(fixture.product.getPrice());
        item.setTaxAmount(BigDecimal.ZERO);
        item.setStockApplied(true);
        order.setItems(new ArrayList<>(List.of(item)));
        orderRepository.save(order);

        OutboxMessage message = new OutboxMessage();
        message.setOrder(order);
        message.setStore(fixture.store);
        message.setType(OutboxType.POS_ORDER);
        message.setCompanyId(fixture.companyId);
        message.setDedupKey("race-test:" + seq);
        message.setStatus(status);
        message.setNextAttemptAt(Instant.now());
        message.setCreatedAt(Instant.now());
        outboxRepository.save(message);
    }

    private int reloadStock(Product product) {
        return productRepository.findById(product.getId()).orElseThrow().getStock();
    }
}
