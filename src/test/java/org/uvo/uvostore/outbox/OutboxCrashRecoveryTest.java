package org.uvo.uvostore.outbox;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;
import org.uvo.uvostore.entity.catalog.Category;
import org.uvo.uvostore.entity.catalog.Product;
import org.uvo.uvostore.entity.catalog.enums.ProductType;
import org.uvo.uvostore.entity.order.Order;
import org.uvo.uvostore.entity.order.OrderItem;
import org.uvo.uvostore.entity.order.enums.FulfillmentStatus;
import org.uvo.uvostore.entity.order.enums.OrderStatus;
import org.uvo.uvostore.entity.order.enums.PaymentStatus;
import org.uvo.uvostore.entity.outbox.OutboxMessage;
import org.uvo.uvostore.entity.outbox.enums.OutboxChannel;
import org.uvo.uvostore.entity.outbox.enums.OutboxStatus;
import org.uvo.uvostore.entity.outbox.enums.OutboxType;
import org.uvo.uvostore.entity.pos.PosConnection;
import org.uvo.uvostore.entity.pos.ProductSyncMapping;
import org.uvo.uvostore.entity.pos.enums.SyncDirection;
import org.uvo.uvostore.entity.pos.enums.SyncStatus;
import org.uvo.uvostore.entity.settings.Setting;
import org.uvo.uvostore.entity.tenant.Store;
import org.uvo.uvostore.entity.tenant.enums.StoreStatus;
import org.uvo.uvostore.repository.CategoryRepository;
import org.uvo.uvostore.repository.OrderRepository;
import org.uvo.uvostore.repository.OutboxMessageRepository;
import org.uvo.uvostore.repository.PosConnectionRepository;
import org.uvo.uvostore.repository.ProductRepository;
import org.uvo.uvostore.repository.ProductSyncMappingRepository;
import org.uvo.uvostore.repository.SettingRepository;
import org.uvo.uvostore.repository.StoreRepository;
import org.uvo.uvostore.service.notification.EmailService;
import org.uvo.uvostore.service.order.OrderStatusService;
import org.uvo.uvostore.service.outbox.OutboxDispatcher;
import org.uvo.uvostore.service.pos.PosClient;
import org.uvo.uvostore.service.pos.PosDelivery;
import org.uvo.uvostore.service.pos.PosOrderPayloadMapper;
import org.uvo.uvostore.support.WithPosIntegration;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PROD-13b. Los dos estados en los que puede quedar una venta al POS si el proceso muere, y lo que hace el
 * despachador con cada uno al volver.
 *
 * <ul>
 *   <li><b>Murió antes del primer intento</b>: la fila está PENDING con 0 intentos. Es exactamente el hueco
 *       que el reintento anterior no veía (solo miraba {@code sync_attempts >= 1}): esa venta no llegaba
 *       nunca al POS. Ahora se entrega, una vez.</li>
 *   <li><b>Murió durante la llamada</b>: la fila quedó IN_FLIGHT. No se sabe si el POS la registró, así que
 *       sin idempotencia confirmada no se reenvía: queda FAILED para revisión manual.</li>
 * </ul>
 *
 * <p>Como {@code StockRecoveryTest}, <b>no</b> extiende {@code IntegrationTestSupport}: el despachador
 * confirma cada cambio de estado en su propia transacción, y la transacción con rollback de esa base los
 * escondería. Todo se confirma de verdad, así que la limpieza es manual. El POS es un {@code @MockitoBean}:
 * lo que se mide es cuántas veces se le llama.
 */
@WithPosIntegration
class OutboxCrashRecoveryTest {

    @MockitoBean
    private PosClient posClient;
    @MockitoBean
    private EmailService emailService;

    @Autowired
    private OutboxDispatcher dispatcher;
    @Autowired
    private OrderStatusService orderStatusService;
    @Autowired
    private OutboxMessageRepository outboxRepository;
    @Autowired
    private StoreRepository storeRepository;
    @Autowired
    private SettingRepository settingRepository;
    @Autowired
    private CategoryRepository categoryRepository;
    @Autowired
    private ProductRepository productRepository;
    @Autowired
    private OrderRepository orderRepository;
    @Autowired
    private PosConnectionRepository posConnectionRepository;
    @Autowired
    private ProductSyncMappingRepository mappingRepository;
    @Autowired
    private TransactionTemplate transactionTemplate;

    private final List<Long> createdOrders = new ArrayList<>();
    private final List<ProductSyncMapping> createdMappings = new ArrayList<>();
    private final List<PosConnection> createdConnections = new ArrayList<>();
    private final List<Product> createdProducts = new ArrayList<>();
    private final List<Category> createdCategories = new ArrayList<>();
    private final List<Setting> createdSettings = new ArrayList<>();
    private final List<Store> createdStores = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        // Las filas del outbox se van con la orden (ON DELETE CASCADE). order_items referencia productos
        // con ON DELETE RESTRICT, así que las órdenes van primero.
        orderRepository.deleteAllById(createdOrders);
        mappingRepository.deleteAll(createdMappings);
        posConnectionRepository.deleteAll(createdConnections);
        productRepository.deleteAll(createdProducts);
        categoryRepository.deleteAll(createdCategories);
        settingRepository.deleteAll(createdSettings);
        storeRepository.deleteAll(createdStores);
    }

    @Test
    @DisplayName("Una venta encolada que nunca llegó a intentarse se entrega al volver, una sola vez")
    void aSaleNeverAttemptedIsDeliveredOnce() {
        Fixture f = fixture();
        Order order = paidOrder(f);
        OutboxMessage row = posRow(order, f.connection().getCompanyId(), OutboxStatus.PENDING, 0, Instant.now());
        when(posClient.notifyOrder(any(), any())).thenReturn(PosDelivery.sent(Map.of()));

        dispatcher.dispatch(OutboxChannel.POS);
        dispatcher.dispatch(OutboxChannel.POS);

        verify(posClient, times(1)).notifyOrder(any(), any());
        OutboxMessage after = reload(row);
        assertThat(after.getStatus()).isEqualTo(OutboxStatus.SENT);
        assertThat(after.getAttempts()).isEqualTo(1);
        assertThat(after.getProcessedAt()).isNotNull();
    }

    @Test
    @DisplayName("Una venta que quedó en vuelo tras la caída no se reenvía: queda FAILED para revisión")
    void aSaleLeftInFlightIsNotResent() {
        Fixture f = fixture();
        Order order = paidOrder(f);
        OutboxMessage row = posRow(order, f.connection().getCompanyId(), OutboxStatus.IN_FLIGHT, 1,
                Instant.now().minus(Duration.ofMinutes(10)));

        dispatcher.dispatch(OutboxChannel.POS);

        verify(posClient, never()).notifyOrder(any(), any());
        OutboxMessage after = reload(row);
        assertThat(after.getStatus()).isEqualTo(OutboxStatus.FAILED);
        assertThat(after.getLastError()).contains("Huérfana en vuelo");
    }

    @Test
    @DisplayName("Una venta en vuelo hace poco no es huérfana: puede seguir su curso en otro proceso")
    void aRecentInFlightSaleIsLeftAlone() {
        Fixture f = fixture();
        Order order = paidOrder(f);
        OutboxMessage row = posRow(order, f.connection().getCompanyId(), OutboxStatus.IN_FLIGHT, 1, Instant.now());

        dispatcher.dispatch(OutboxChannel.POS);

        verify(posClient, never()).notifyOrder(any(), any());
        assertThat(reload(row).getStatus()).isEqualTo(OutboxStatus.IN_FLIGHT);
    }

    @Test
    @DisplayName("Del pago confirmado al POS: lo que escribe markPaid es lo que entrega el despachador")
    void fromThePaymentToThePos() {
        Fixture f = fixture();
        Order order = pendingOrder(f);
        when(posClient.notifyOrder(any(), any())).thenReturn(PosDelivery.sent(Map.of()));

        transactionTemplate.executeWithoutResult(status ->
                orderStatusService.markPaid(order.getId(), "pi_crash", order.getTotal()));
        dispatcher.dispatch(OutboxChannel.POS);

        verify(posClient, times(1)).notifyOrder(any(), any());
        assertThat(outboxRepository.findByOrderIdOrderByIdAsc(order.getId()))
                .filteredOn(row -> row.getType() == OutboxType.POS_ORDER)
                .singleElement()
                .satisfies(row -> assertThat(row.getStatus()).isEqualTo(OutboxStatus.SENT));
    }

    @Test
    @DisplayName("Del checkout al correo: el acuse que quedó encolado sale al volver, una sola vez")
    void aQueuedEmailIsDeliveredOnce() {
        Fixture f = fixture();
        Order order = pendingOrder(f);
        OutboxMessage row = outboxRepository.save(OutboxMessage.builder()
                .order(order)
                .type(OutboxType.ORDER_PLACED_EMAIL)
                .dedupKey("email:placed:" + order.getId())
                .status(OutboxStatus.PENDING)
                .attempts(0)
                .nextAttemptAt(Instant.now().minusSeconds(1))
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build());
        when(emailService.deliver(any(), any(), any())).thenReturn(EmailService.Delivery.SENT);

        dispatcher.dispatch(OutboxChannel.MAIL);
        dispatcher.dispatch(OutboxChannel.MAIL);

        verify(emailService, times(1)).deliver(any(), any(), any());
        assertThat(reload(row).getStatus()).isEqualTo(OutboxStatus.SENT);
        // El POS va por su propio canal: despachar el correo no lo toca.
        verify(posClient, never()).notifyOrder(any(), any());
    }

    // --- fixtures ----------------------------------------------------------------------------------

    private record Fixture(Store store, PosConnection connection, Product product) {
    }

    private Fixture fixture() {
        long seq = System.nanoTime();
        Store store = storeRepository.save(Store.builder()
                .name("Outbox Store").slug("outbox-" + seq).status(StoreStatus.ACTIVE).build());
        createdStores.add(store);

        Setting setting = new Setting();
        setting.setStore(store);
        setting.setSettingKey("pos_sync_enabled");
        setting.setValue("true");
        createdSettings.add(settingRepository.save(setting));

        Category category = categoryRepository.save(Category.builder()
                .store(store).name("Outbox " + seq).slug("outbox-cat-" + seq).active(true).build());
        createdCategories.add(category);
        Product product = productRepository.save(Product.builder()
                .store(store).category(category)
                .name("Artículo " + seq).slug("outbox-prod-" + seq).sku("OUTBOX-" + seq)
                .productType(ProductType.SIMPLE)
                .price(BigDecimal.valueOf(1000))
                .stock(10).manageStock(true).active(true)
                .build());
        createdProducts.add(product);

        PosConnection connection = new PosConnection();
        connection.setStore(store);
        connection.setCompanyName("Empresa " + seq);
        connection.setCompanyId(seq);
        connection.setApiUrl("https://pos.test.local/api");
        connection.setApiKey("api-key");
        connection.setWebhookSecret("webhook-secret");
        connection.setActive(true);
        connection = posConnectionRepository.save(connection);
        createdConnections.add(connection);

        ProductSyncMapping mapping = new ProductSyncMapping();
        mapping.setProduct(product);
        mapping.setExternalId(seq);
        mapping.setExternalSku(product.getSku());
        mapping.setCompanyId(connection.getCompanyId());
        mapping.setSyncStatus(SyncStatus.ACTIVE);
        mapping.setSyncDirection(SyncDirection.FROM_POS);
        mapping.setLastSyncedAt(Instant.now());
        createdMappings.add(mappingRepository.save(mapping));

        return new Fixture(store, connection, product);
    }

    private Order paidOrder(Fixture f) {
        Order order = newOrder(f);
        order.setStatus(OrderStatus.PROCESSING);
        order.setPaymentStatus(PaymentStatus.PAID);
        order.setPaidAt(Instant.now());
        return save(order);
    }

    private Order pendingOrder(Fixture f) {
        return save(newOrder(f));
    }

    private Order save(Order order) {
        Order saved = orderRepository.save(order);
        createdOrders.add(saved.getId());
        return saved;
    }

    private Order newOrder(Fixture f) {
        Order order = new Order();
        order.setStore(f.store());
        order.setOrderNumber("ORD-OUTBOX-" + System.nanoTime());
        order.setCustomerEmail("comprador@test.local");
        order.setCustomerFirstName("Test");
        order.setCustomerLastName("Comprador");
        order.setSubtotal(BigDecimal.valueOf(1000));
        order.setDiscountAmount(BigDecimal.ZERO);
        order.setShippingCost(BigDecimal.ZERO);
        order.setTaxAmount(BigDecimal.ZERO);
        order.setTotal(BigDecimal.valueOf(1000));
        order.setStatus(OrderStatus.PENDING);
        order.setPaymentStatus(PaymentStatus.PENDING);
        order.setFulfillmentStatus(FulfillmentStatus.UNFULFILLED);

        OrderItem item = new OrderItem();
        item.setOrder(order);
        item.setProduct(f.product());
        item.setProductName(f.product().getName());
        item.setProductSku(f.product().getSku());
        item.setQuantity(1);
        item.setPrice(f.product().getPrice());
        item.setSubtotal(f.product().getPrice());
        item.setTaxAmount(BigDecimal.ZERO);
        List<OrderItem> items = new ArrayList<>();
        items.add(item);
        order.setItems(items);
        return order;
    }

    /** La fila tal como la deja la caída: escrita y confirmada, en el estado que se pida. */
    private OutboxMessage posRow(Order order, Long companyId, OutboxStatus status, int attempts, Instant updatedAt) {
        return outboxRepository.save(OutboxMessage.builder()
                .order(order)
                .type(OutboxType.POS_ORDER)
                .companyId(companyId)
                .dedupKey("pos:" + PosOrderPayloadMapper.idempotencyKey(order.getOrderNumber(), companyId))
                .status(status)
                .attempts(attempts)
                .nextAttemptAt(Instant.now().minusSeconds(1))
                .createdAt(updatedAt)
                .updatedAt(updatedAt)
                .build());
    }

    private OutboxMessage reload(OutboxMessage row) {
        return outboxRepository.findById(row.getId()).orElseThrow();
    }
}
