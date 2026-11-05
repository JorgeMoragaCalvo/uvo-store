package org.uvo.uvostore.support;

import cl.transbank.webpay.webpayplus.WebpayPlus;
import cl.transbank.webpay.webpayplus.responses.WebpayPlusMallTransactionCommitResponse;
import cl.transbank.webpay.webpayplus.responses.WebpayPlusMallTransactionRefundResponse;
import cl.transbank.webpay.webpayplus.responses.WebpayPlusMallTransactionStatusResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.AdditionalAnswers;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.uvo.uvostore.entity.catalog.Category;
import org.uvo.uvostore.entity.catalog.Product;
import org.uvo.uvostore.entity.catalog.enums.ProductType;
import org.uvo.uvostore.entity.order.Coupon;
import org.uvo.uvostore.entity.order.CouponUsage;
import org.uvo.uvostore.entity.order.Order;
import org.uvo.uvostore.entity.order.OrderItem;
import org.uvo.uvostore.entity.order.enums.CouponType;
import org.uvo.uvostore.entity.order.enums.FulfillmentStatus;
import org.uvo.uvostore.entity.order.enums.OrderStatus;
import org.uvo.uvostore.entity.order.enums.PaymentMethodType;
import org.uvo.uvostore.entity.order.enums.PaymentStatus;
import org.uvo.uvostore.entity.outbox.OutboxMessage;
import org.uvo.uvostore.entity.payment.PaymentGatewayConfig;
import org.uvo.uvostore.entity.payment.enums.PaymentGatewayType;
import org.uvo.uvostore.entity.pos.PosConnection;
import org.uvo.uvostore.entity.pos.ProductSyncMapping;
import org.uvo.uvostore.entity.pos.enums.SyncDirection;
import org.uvo.uvostore.entity.pos.enums.SyncStatus;
import org.uvo.uvostore.entity.settings.Setting;
import org.uvo.uvostore.entity.tenant.Store;
import org.uvo.uvostore.repository.CategoryRepository;
import org.uvo.uvostore.repository.CouponRepository;
import org.uvo.uvostore.repository.CouponUsageRepository;
import org.uvo.uvostore.repository.OrderRepository;
import org.uvo.uvostore.repository.OutboxMessageRepository;
import org.uvo.uvostore.repository.PaymentGatewayConfigRepository;
import org.uvo.uvostore.repository.PermissionRepository;
import org.uvo.uvostore.repository.PosConnectionRepository;
import org.uvo.uvostore.repository.ProductRepository;
import org.uvo.uvostore.repository.ProductSyncMappingRepository;
import org.uvo.uvostore.repository.RoleRepository;
import org.uvo.uvostore.repository.SettingRepository;
import org.uvo.uvostore.repository.StoreRepository;
import org.uvo.uvostore.repository.UserRepository;
import org.uvo.uvostore.security.TenantContext;
import org.uvo.uvostore.service.notification.EmailService;
import org.uvo.uvostore.service.order.OrderInventoryService;
import org.uvo.uvostore.service.AdvisoryLock;
import org.uvo.uvostore.service.metrics.BusinessMetrics;
import org.uvo.uvostore.service.order.AbandonedOrderJob;
import org.uvo.uvostore.service.order.OrderStatusService;
import org.uvo.uvostore.service.order.StockRecoveryJob;
import org.uvo.uvostore.service.outbox.OutboxDispatcher;
import org.uvo.uvostore.service.outbox.OutboxHandler;
import org.uvo.uvostore.service.outbox.OutboxStore;
import org.uvo.uvostore.service.payment.RefundIntentStore;
import org.uvo.uvostore.service.payment.WebpayService;
import org.uvo.uvostore.service.payment.WebpayServiceImpl;
import org.uvo.uvostore.service.pos.PosClient;
import org.uvo.uvostore.service.url.StorePublicUrlResolver;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * GAP-04. Base de las pruebas que interrumpen un flujo antes o después de cada commit o llamada externa, lo
 * "reinician" y comprueban que la entrega, el saldo y el stock quedan exactos.
 *
 * <p><b>Cómo se interrumpe.</b> No se mata la JVM: el bean del paso siguiente lanza una vez, justo en ese punto
 * ({@code @MockitoSpyBean}), y la transacción en curso se deshace como lo haría con el proceso muerto. Lo que ya
 * se había confirmado queda, que es exactamente lo que hay que mirar. <b>Cómo se reinicia:</b> se llama al job que
 * en producción recoge ese estado (la conciliación, el barrido de stock, el despachador, el job de órdenes
 * abandonadas), sobre lo que quedó confirmado en la base.
 *
 * <p>Todo se confirma de verdad, como en {@code OutboxCrashRecoveryTest}: con la transacción con rollback de
 * {@code IntegrationTestSupport} no se vería qué sobrevive. La limpieza borra cada tienda creada
 * ({@link CommittedFixtures#purgeStore}).
 *
 * <p>Las pasarelas y el POS son dobles: Transbank ({@link #TRANSBANK}, por debajo del {@code WebpayServiceImpl}
 * real, que así ejercita su propio código), {@link PosClient} y {@link EmailService}. Las subclases comparten un
 * solo contexto mientras ninguna declare otro bean sustituido.
 */
@SpringBootTest(properties = {
        "app.pos.enabled=true",
        // Los jobs de este contexto no corren solos durante las pruebas: cada una los llama cuando toca, y un barrido a
        // destiempo cambiaría lo que se mide. El despachador ya va apagado para toda la suite (pom.xml).
        "app.stock-recovery.interval-ms=86400000",
        "app.reconciliation.interval-ms=86400000",
        "app.abandoned-orders.interval-ms=86400000",
        "app.pos.reconciliation.interval-ms=86400000"})
@Import(InterruptionTestSupport.FakeTransbank.class)
public abstract class InterruptionTestSupport {

    /** Transbank. Se reinicia después de cada prueba. */
    protected static final WebpayPlus.MallTransaction TRANSBANK = Mockito.mock(WebpayPlus.MallTransaction.class);

    protected static final BigDecimal PRICE = new BigDecimal("1000");

    /**
     * Si no es null, la próxima vez que el servicio vaya a hablar con Transbank lanza esto en su lugar, una vez: el
     * proceso murió justo antes de la llamada, con todo lo anterior ya confirmado.
     */
    protected static volatile RuntimeException crashBeforeTransbank;

    @MockitoBean
    protected PosClient posClient;
    @MockitoBean
    protected EmailService emailService;

    // Los puntos donde se corta. Sin stub se comportan como el bean real.
    @MockitoSpyBean
    protected OutboxStore outboxStore;
    @MockitoSpyBean
    protected OrderStatusService orderStatusService;
    @MockitoSpyBean
    protected OrderInventoryService orderInventoryService;
    @MockitoSpyBean
    protected RefundIntentStore refundIntentStore;

    /** El {@code WebpayServiceImpl} real sobre {@link #TRANSBANK}. */
    @Autowired
    protected WebpayService webpayService;

    @Autowired
    protected PlatformTransactionManager transactionManager;
    @Autowired
    protected JdbcTemplate jdbc;
    @Autowired
    protected OrderRepository orderRepository;
    @Autowired
    protected OutboxMessageRepository outboxRepository;
    @Autowired
    private StoreRepository storeRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private RoleRepository roleRepository;
    @Autowired
    private PermissionRepository permissionRepository;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private SettingRepository settingRepository;
    @Autowired
    private CategoryRepository categoryRepository;
    @Autowired
    private ProductRepository productRepository;
    @Autowired
    private PosConnectionRepository posConnectionRepository;
    @Autowired
    private ProductSyncMappingRepository mappingRepository;
    @Autowired
    private PaymentGatewayConfigRepository gatewayConfigRepository;
    @Autowired
    private CouponRepository couponRepository;
    @Autowired
    private CouponUsageRepository couponUsageRepository;
    @Autowired
    private List<OutboxHandler> outboxHandlers;
    @Autowired
    private AdvisoryLock advisoryLock;
    @Autowired
    private BusinessMetrics businessMetrics;

    protected CommittedFixtures fixtures;
    protected TransactionTemplate tx;
    private final List<Long> createdStores = new ArrayList<>();

    @TestConfiguration
    static class FakeTransbank {

        /** El servicio real, con Transbank sustituido en su único punto de salida (ver {@code transaction()}). */
        @Bean
        @Primary
        WebpayServiceImpl interruptionWebpayService(OrderRepository orderRepository,
                                                    PaymentGatewayConfigRepository configRepository,
                                                    OrderStatusService orderStatusService,
                                                    StorePublicUrlResolver publicUrls) {
            return new WebpayServiceImpl(orderRepository, configRepository, orderStatusService,
                    "597055555535", "api-key", "integration", publicUrls, 20000) {
                @Override
                protected WebpayPlus.MallTransaction transaction() {
                    RuntimeException crash = crashBeforeTransbank;
                    if (crash != null) {
                        crashBeforeTransbank = null;
                        throw crash;
                    }
                    return TRANSBANK;
                }
            };
        }
    }

    @BeforeEach
    void setUpInterruptionFixtures() {
        fixtures = new CommittedFixtures(transactionManager, jdbc, storeRepository, userRepository, roleRepository,
                permissionRepository, orderRepository, passwordEncoder);
        tx = new TransactionTemplate(transactionManager);
    }

    @AfterEach
    void purgeInterruptionFixtures() {
        Mockito.reset(TRANSBANK);
        crashBeforeTransbank = null;
        TenantContext.clear();
        createdStores.forEach(fixtures::purgeStore);
        createdStores.clear();
    }

    // --- datos ----------------------------------------------------------------------------------------------------

    /** Una tienda con un producto, la integración con el POS encendida y mapeada, y Webpay habilitado. */
    protected record Shop(Store store, Product product, Long companyId) {
    }

    protected Shop shop(int stock) {
        return tx.execute(status -> {
            long seq = System.nanoTime();
            Store store = fixtures.store("gap04");
            createdStores.add(store.getId());

            Setting setting = new Setting();
            setting.setStore(store);
            setting.setSettingKey("pos_sync_enabled");
            setting.setValue("true");
            settingRepository.save(setting);

            Category category = categoryRepository.save(Category.builder()
                    .store(store).name("Interrupción " + seq).slug("gap04-cat-" + seq).active(true).build());
            Product product = productRepository.save(Product.builder()
                    .store(store).category(category)
                    .name("Artículo " + seq).slug("gap04-prod-" + seq).sku("GAP04-" + seq)
                    .productType(ProductType.SIMPLE)
                    .price(PRICE)
                    .stock(stock).manageStock(true).active(true)
                    .build());

            PosConnection connection = new PosConnection();
            connection.setStore(store);
            connection.setCompanyName("Empresa " + seq);
            connection.setCompanyId(seq);
            connection.setApiUrl("https://pos.test.local/api");
            connection.setApiKey("api-key");
            connection.setWebhookSecret("webhook-secret");
            connection.setActive(true);
            posConnectionRepository.save(connection);

            ProductSyncMapping mapping = new ProductSyncMapping();
            mapping.setProduct(product);
            mapping.setExternalId(seq);
            mapping.setExternalSku(product.getSku());
            mapping.setCompanyId(seq);
            mapping.setSyncStatus(SyncStatus.ACTIVE);
            mapping.setSyncDirection(SyncDirection.FROM_POS);
            mapping.setLastSyncedAt(java.time.Instant.now());
            mappingRepository.save(mapping);

            PaymentGatewayConfig webpay = new PaymentGatewayConfig();
            webpay.setStore(store);
            webpay.setGateway(PaymentGatewayType.WEBPAY);
            webpay.setEnabled(true);
            Map<String, String> credentials = new HashMap<>();
            credentials.put("childCommerceCode", "597055555536");
            webpay.setCredentials(credentials);
            gatewayConfigRepository.save(webpay);

            return new Shop(store, product, seq);
        });
    }

    /** Un cupón de monto fijo, todavía sin usar. */
    protected Coupon coupon(Shop shop) {
        return tx.execute(status -> couponRepository.save(Coupon.builder()
                .store(shop.store())
                .code("GAP04-" + System.nanoTime())
                .name("Cupón de prueba")
                .type(CouponType.FIXED)
                .value(new BigDecimal("100"))
                .build()));
    }

    /**
     * Una orden de Webpay como la deja el checkout más {@code createTransaction}: pendiente, con su token y, si se
     * pide, con el cupón ya reservado ({@code times_used} + 1 y su fila de uso), igual que {@code claimUsage}.
     */
    protected Order pendingWebpayOrder(Shop shop, int quantity, Coupon coupon) {
        return tx.execute(status -> {
            BigDecimal subtotal = PRICE.multiply(BigDecimal.valueOf(quantity));
            BigDecimal discount = coupon == null ? BigDecimal.ZERO : coupon.getValue();

            Order order = new Order();
            order.setStore(shop.store());
            order.setOrderNumber("ORD-GAP04-" + System.nanoTime());
            order.setCustomerEmail("comprador@test.local");
            order.setCustomerFirstName("Test");
            order.setCustomerLastName("Comprador");
            order.setSubtotal(subtotal);
            order.setDiscountAmount(discount);
            order.setShippingCost(BigDecimal.ZERO);
            order.setTaxAmount(BigDecimal.ZERO);
            order.setTotal(subtotal.subtract(discount));
            order.setStatus(OrderStatus.PENDING);
            order.setPaymentStatus(PaymentStatus.PENDING);
            order.setPaymentMethod(PaymentMethodType.WEBPAY);
            order.setFulfillmentStatus(FulfillmentStatus.UNFULFILLED);
            order.setPaymentId("tok-" + System.nanoTime());
            if (coupon != null) {
                order.setCoupon(coupon);
                order.setCouponCode(coupon.getCode());
            }

            OrderItem item = new OrderItem();
            item.setOrder(order);
            item.setProduct(shop.product());
            item.setProductName(shop.product().getName());
            item.setProductSku(shop.product().getSku());
            item.setQuantity(quantity);
            item.setPrice(PRICE);
            item.setSubtotal(subtotal);
            item.setTaxAmount(BigDecimal.ZERO);
            List<OrderItem> items = new ArrayList<>();
            items.add(item);
            order.setItems(items);
            Order saved = orderRepository.save(order);

            if (coupon != null) {
                couponRepository.claimUsage(coupon.getId());
                couponUsageRepository.save(CouponUsage.builder()
                        .coupon(coupon).order(saved).discountAmount(discount).build());
            }
            return saved;
        });
    }

    // --- los jobs, limitados a la tienda de la prueba ----------------------------------------------------------------
    //
    // Los jobs recorren todas las tiendas, y la base de las pruebas es la local: llamarlos tal cual cancelaría las
    // órdenes viejas de otras tiendas o despacharía su outbox contra el POS simulado. Se construyen con la misma clase
    // y los mismos beans, salvo la consulta que elige qué recoger, que solo ve la tienda de la prueba.

    protected OutboxDispatcher dispatcher(Shop shop) {
        Long storeId = shop.store().getId();
        OutboxMessageRepository scoped = Mockito.mock(OutboxMessageRepository.class,
                AdditionalAnswers.delegatesTo(outboxRepository));
        Mockito.doAnswer(inv -> outboxRepository.findDue(inv.getArgument(0), inv.getArgument(1), Pageable.unpaged())
                        .stream().filter(m -> m.getOrder().getStore().getId().equals(storeId)).toList())
                .when(scoped).findDue(Mockito.any(), Mockito.any(), Mockito.any());
        Mockito.doAnswer(inv -> outboxRepository.findOrphans(inv.getArgument(0), inv.getArgument(1), Pageable.unpaged())
                        .stream().filter(m -> m.getOrder().getStore().getId().equals(storeId)).toList())
                .when(scoped).findOrphans(Mockito.any(), Mockito.any(), Mockito.any());
        // Los mismos valores que application.properties, con la venta incierta sin reintento (POS-1).
        return new OutboxDispatcher(scoped, outboxStore, advisoryLock, outboxHandlers, false, 50, 48, 120,
                businessMetrics);
    }

    protected AbandonedOrderJob abandonedOrderJob(Shop shop) {
        Long storeId = shop.store().getId();
        OrderRepository scoped = scopedOrders();
        Mockito.doAnswer(inv -> orderRepository.findAbandonedPending(inv.getArgument(0), Pageable.unpaged())
                        .stream().filter(o -> o.getStore().getId().equals(storeId)).toList())
                .when(scoped).findAbandonedPending(Mockito.any(), Mockito.any());
        return new AbandonedOrderJob(scoped, orderStatusService, 24, 50);
    }

    protected StockRecoveryJob stockRecoveryJob(Shop shop) {
        Long storeId = shop.store().getId();
        OrderRepository scoped = scopedOrders();
        Mockito.doAnswer(inv -> orderRepository.findPaidWithoutStockApplied(inv.getArgument(0), Pageable.unpaged())
                        .stream().filter(o -> o.getStore().getId().equals(storeId)).toList())
                .when(scoped).findPaidWithoutStockApplied(Mockito.any(), Mockito.any());
        return new StockRecoveryJob(scoped, orderInventoryService, advisoryLock, 15, 50);
    }

    private OrderRepository scopedOrders() {
        return Mockito.mock(OrderRepository.class, AdditionalAnswers.delegatesTo(orderRepository));
    }

    /** El regreso del comprador desde Transbank con el pago autorizado: {@code commitTransaction}, de punta a punta. */
    protected Order pay(Shop shop, Order order) throws Exception {
        Mockito.when(TRANSBANK.commit(order.getPaymentId())).thenReturn(authorizedCommit(order.getTotal()));
        TenantContext.runWithin(shop.store(), () -> webpayService.commitTransaction(order.getPaymentId()));
        return reload(order);
    }

    // --- Transbank ------------------------------------------------------------------------------------------------

    protected static WebpayPlusMallTransactionCommitResponse authorizedCommit(BigDecimal amount) {
        WebpayPlusMallTransactionCommitResponse response = new WebpayPlusMallTransactionCommitResponse();
        // Detail es una clase interna no estática del SDK (ver WebpayReconcileTest).
        WebpayPlusMallTransactionCommitResponse.Detail detail = response.new Detail();
        detail.setStatus("AUTHORIZED");
        detail.setResponseCode((byte) 0);
        detail.setAmount(amount.doubleValue());
        response.setDetails(List.of(detail));
        return response;
    }

    protected static WebpayPlusMallTransactionStatusResponse authorizedStatus(BigDecimal amount) {
        WebpayPlusMallTransactionStatusResponse response = new WebpayPlusMallTransactionStatusResponse();
        WebpayPlusMallTransactionStatusResponse.Detail detail = response.new Detail();
        detail.setStatus("AUTHORIZED");
        detail.setResponseCode((byte) 0);
        detail.setAmount(amount.doubleValue());
        response.setDetails(List.of(detail));
        return response;
    }

    protected static WebpayPlusMallTransactionRefundResponse refundAccepted() {
        WebpayPlusMallTransactionRefundResponse response = new WebpayPlusMallTransactionRefundResponse();
        response.setType("REVERSED");
        response.setResponseCode((byte) 0);
        return response;
    }

    // --- lo que se mide, leído de la base y no de entidades en memoria ---------------------------------------------

    protected Order reload(Order order) {
        return orderRepository.findById(order.getId()).orElseThrow();
    }

    protected int stock(Product product) {
        return jdbc.queryForObject("SELECT stock FROM products WHERE id = ?", Integer.class, product.getId());
    }

    protected int timesUsed(Coupon coupon) {
        return jdbc.queryForObject("SELECT times_used FROM coupons WHERE id = ?", Integer.class, coupon.getId());
    }

    protected int couponUsages(Order order) {
        return jdbc.queryForObject("SELECT count(*) FROM coupon_usages WHERE order_id = ?", Integer.class, order.getId());
    }

    protected List<String> notes(Order order) {
        return jdbc.queryForList("SELECT notes FROM order_status_history WHERE order_id = ? ORDER BY id",
                String.class, order.getId());
    }

    /** Los reembolsos de la orden, en orden de creación: {@code [estado, clave de idempotencia]}. */
    protected List<Map<String, Object>> refunds(Order order) {
        return jdbc.queryForList("SELECT status, idempotency_key FROM order_refunds WHERE order_id = ? ORDER BY id",
                order.getId());
    }

    protected List<OutboxMessage> outbox(Order order) {
        return outboxRepository.findByOrderIdOrderByIdAsc(order.getId());
    }

    /** Mueve hacia atrás una marca de tiempo de la orden, para que el job que la busca la vea "vieja". */
    protected void backdate(Order order, String column, Duration by) {
        jdbc.update("UPDATE orders SET " + column + " = " + column + " - make_interval(secs => ?) WHERE id = ?",
                (double) by.toSeconds(), order.getId());
    }
}
