package org.uvo.uvostore.outbox;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
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
import org.uvo.uvostore.entity.pos.enums.SyncDirection;
import org.uvo.uvostore.entity.pos.enums.SyncStatus;
import org.uvo.uvostore.entity.tenant.Store;
import org.uvo.uvostore.repository.OrderRepository;
import org.uvo.uvostore.repository.OutboxMessageRepository;
import org.uvo.uvostore.repository.PosConnectionRepository;
import org.uvo.uvostore.repository.ProductSyncMappingRepository;
import org.uvo.uvostore.service.order.OrderStatusService;
import org.uvo.uvostore.service.outbox.OutboxService;
import org.uvo.uvostore.service.pos.PosOrderPayloadMapper;
import org.uvo.uvostore.support.IntegrationTestSupport;
import org.uvo.uvostore.support.WithPosIntegration;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PROD-13b. La venta al POS se encola en la misma transacción que confirma el pago: una fila por empresa,
 * con la clave de idempotencia que viaja al POS como clave de deduplicación.
 *
 * <p>Con {@code IntegrationTestSupport} a propósito: lo que se comprueba es justo lo que escribe esa
 * transacción, y las filas se ven dentro de ella. La entrega, con transacciones de verdad, está en
 * {@code OutboxCrashRecoveryTest}.
 */
@WithPosIntegration
class OutboxPosEnqueueTest extends IntegrationTestSupport {

    @Autowired
    private OrderStatusService orderStatusService;
    @Autowired
    private OutboxService outboxService;
    @Autowired
    private OrderRepository orderRepository;
    @Autowired
    private OutboxMessageRepository outboxRepository;
    @Autowired
    private PosConnectionRepository posConnectionRepository;
    @Autowired
    private ProductSyncMappingRepository mappingRepository;

    @Test
    @DisplayName("Al confirmar el pago se encola una venta por empresa, con su clave de idempotencia")
    void markingPaidQueuesOneSalePerCompany() {
        Store store = posStore("enq-two");
        PosConnection companyA = connection(store);
        PosConnection companyB = connection(store);
        Product shared = createProduct(store, createCategory(store, "Cat"), "Compartido", BigDecimal.valueOf(1000));
        Product onlyB = createProduct(store, createCategory(store, "Cat B"), "Solo B", BigDecimal.valueOf(2000));
        // El producto mapeado a dos empresas es el caso que hacía lanzar a findByProductId dentro del pago.
        mapping(companyA, shared);
        mapping(companyB, shared);
        mapping(companyB, onlyB);
        Order order = pendingOrder(store, shared, onlyB);

        orderStatusService.markPaid(order.getId(), "pi_" + nextSeq(), order.getTotal());

        List<OutboxMessage> rows = posRows(order);
        assertThat(rows).extracting(OutboxMessage::getCompanyId)
                .containsExactlyInAnyOrder(companyA.getCompanyId(), companyB.getCompanyId());
        assertThat(rows).extracting(OutboxMessage::getDedupKey).containsExactlyInAnyOrder(
                "pos:" + PosOrderPayloadMapper.idempotencyKey(order.getOrderNumber(), companyA.getCompanyId()),
                "pos:" + PosOrderPayloadMapper.idempotencyKey(order.getOrderNumber(), companyB.getCompanyId()));
        assertThat(rows).allSatisfy(row -> {
            assertThat(row.getStatus()).isEqualTo(OutboxStatus.PENDING);
            assertThat(row.getAttempts()).isZero();
        });
    }

    @Test
    @DisplayName("Con la tienda sin sincronización con el POS no se encola nada")
    void nothingIsQueuedWhenTheStoreDoesNotSync() {
        Store store = createStore("enq-off");
        disableShipping(store);
        setSetting(store, "pos_sync_enabled", "false");
        PosConnection company = connection(store);
        Product product = createProduct(store, createCategory(store, "Cat"), "Producto", BigDecimal.valueOf(1000));
        mapping(company, product);
        Order order = pendingOrder(store, product);

        orderStatusService.markPaid(order.getId(), "pi_" + nextSeq(), order.getTotal());

        assertThat(posRows(order)).isEmpty();
    }

    @Test
    @DisplayName("Un pago con descuadre de monto no encola ninguna venta")
    void anAmountMismatchQueuesNothing() {
        // M4: la orden queda PENDING para revisión. Una venta registrada en el POS por un pago que no
        // coincide sería un documento por un importe que no se cobró.
        Store store = posStore("enq-mismatch");
        PosConnection company = connection(store);
        Product product = createProduct(store, createCategory(store, "Cat"), "Producto", BigDecimal.valueOf(1000));
        mapping(company, product);
        Order order = pendingOrder(store, product);

        orderStatusService.markPaid(order.getId(), "pi_" + nextSeq(), order.getTotal().add(BigDecimal.ONE));

        assertThat(posRows(order)).isEmpty();
    }

    @Test
    @DisplayName("Confirmar el mismo pago dos veces no encola la venta dos veces")
    void confirmingTwiceQueuesOnce() {
        Store store = posStore("enq-twice");
        PosConnection company = connection(store);
        Product product = createProduct(store, createCategory(store, "Cat"), "Producto", BigDecimal.valueOf(1000));
        mapping(company, product);
        Order order = pendingOrder(store, product);

        orderStatusService.markPaid(order.getId(), "pi_" + nextSeq(), order.getTotal());
        orderStatusService.markPaid(order.getId(), "pi_" + nextSeq(), order.getTotal());

        assertThat(posRows(order)).hasSize(1);
    }

    @Test
    @DisplayName("Encolar lo mismo dos veces falla en vez de ignorarse: el dedup es la red de seguridad")
    void queueingTheSameSaleTwiceFails() {
        // Si dos confirmaciones del mismo pago llegaran a encolar a la vez, la segunda tiene que deshacerse
        // entera. Por eso el encolado es un INSERT simple: un "si ya existe, no hagas nada" la dejaría seguir.
        Store store = posStore("enq-dup");
        PosConnection company = connection(store);
        Product product = createProduct(store, createCategory(store, "Cat"), "Producto", BigDecimal.valueOf(1000));
        mapping(company, product);
        Order order = pendingOrder(store, product);
        order.setPaymentStatus(PaymentStatus.PAID);

        outboxService.enqueuePaymentConfirmed(order);

        assertThatThrownBy(() -> outboxService.enqueuePaymentConfirmed(order))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // --- fixtures ----------------------------------------------------------------------------------

    private List<OutboxMessage> posRows(Order order) {
        return outboxRepository.findByOrderIdOrderByIdAsc(order.getId()).stream()
                .filter(row -> row.getType() == OutboxType.POS_ORDER)
                .toList();
    }

    private Store posStore(String prefix) {
        Store store = createStore(prefix);
        disableShipping(store);
        setSetting(store, "pos_sync_enabled", "true");
        return store;
    }

    private PosConnection connection(Store store) {
        PosConnection connection = new PosConnection();
        connection.setStore(store);
        connection.setCompanyName("Empresa " + store.getSlug());
        connection.setCompanyId(System.nanoTime());
        connection.setApiUrl("https://pos.test.local/api");
        connection.setApiKey("api-key");
        connection.setWebhookSecret("webhook-secret");
        connection.setActive(true);
        return posConnectionRepository.save(connection);
    }

    private void mapping(PosConnection connection, Product product) {
        ProductSyncMapping mapping = new ProductSyncMapping();
        mapping.setProduct(product);
        mapping.setExternalId(nextSeq());
        mapping.setExternalSku(product.getSku());
        mapping.setCompanyId(connection.getCompanyId());
        mapping.setSyncStatus(SyncStatus.ACTIVE);
        mapping.setSyncDirection(SyncDirection.FROM_POS);
        mapping.setLastSyncedAt(Instant.now());
        mappingRepository.save(mapping);
    }

    private Order pendingOrder(Store store, Product... products) {
        Order order = new Order();
        order.setStore(store);
        order.setOrderNumber("ORD-ENQ-" + nextSeq());
        order.setCustomerEmail("comprador@test.local");
        order.setCustomerFirstName("Test");
        order.setCustomerLastName("Comprador");
        order.setDiscountAmount(BigDecimal.ZERO);
        order.setShippingCost(BigDecimal.ZERO);
        order.setTaxAmount(BigDecimal.ZERO);
        order.setStatus(OrderStatus.PENDING);
        order.setPaymentStatus(PaymentStatus.PENDING);
        order.setFulfillmentStatus(FulfillmentStatus.UNFULFILLED);

        BigDecimal total = BigDecimal.ZERO;
        List<OrderItem> items = new ArrayList<>();
        for (Product product : products) {
            OrderItem item = new OrderItem();
            item.setOrder(order);
            item.setProduct(product);
            item.setProductName(product.getName());
            item.setProductSku(product.getSku());
            item.setQuantity(1);
            item.setPrice(product.getPrice());
            item.setSubtotal(product.getPrice());
            item.setTaxAmount(BigDecimal.ZERO);
            items.add(item);
            total = total.add(product.getPrice());
        }
        order.setItems(items);
        order.setSubtotal(total);
        order.setTotal(total);
        return orderRepository.save(order);
    }
}
