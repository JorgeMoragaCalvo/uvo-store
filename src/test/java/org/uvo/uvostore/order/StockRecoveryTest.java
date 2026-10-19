package org.uvo.uvostore.order;

import org.hibernate.Hibernate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.support.TransactionTemplate;
import org.uvo.uvostore.entity.catalog.Category;
import org.uvo.uvostore.entity.catalog.Product;
import org.uvo.uvostore.entity.catalog.enums.ProductType;
import org.uvo.uvostore.entity.order.Order;
import org.uvo.uvostore.entity.order.OrderItem;
import org.uvo.uvostore.entity.order.enums.FulfillmentStatus;
import org.uvo.uvostore.entity.order.enums.OrderStatus;
import org.uvo.uvostore.entity.order.enums.PaymentStatus;
import org.uvo.uvostore.entity.tenant.Store;
import org.uvo.uvostore.entity.tenant.enums.StoreStatus;
import org.uvo.uvostore.repository.CategoryRepository;
import org.uvo.uvostore.repository.OrderRepository;
import org.uvo.uvostore.repository.OrderStatusHistoryRepository;
import org.uvo.uvostore.repository.ProductRepository;
import org.uvo.uvostore.repository.StoreRepository;
import org.uvo.uvostore.service.order.OrderInventoryService;
import org.uvo.uvostore.service.order.StockRecoveryJob;
import org.uvo.uvostore.service.order.event.PaymentConfirmedEvent;
import org.uvo.uvostore.service.order.event.StockDecrementListener;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PROD-13a. Una orden cobrada cuyo stock no se descontó —el proceso murió entre el commit de
 * {@code markPaid} y {@code StockDecrementListener}— la recoge {@code StockRecoveryJob}, y el reclamo
 * condicional de {@code applyOrderStock} impide que el barrido y el listener descuenten los dos.
 *
 * <p>Como {@code StockConcurrencyTest}, <b>no</b> extiende {@code IntegrationTestSupport}: su
 * transacción por test aislaría los hilos entre sí y el reclamo no tendría con quién competir. Todo se
 * confirma de verdad, así que la limpieza es manual. La orden "caída" se construye directamente en el
 * estado que deja la caída: PAID, {@code stock_applied = false} y con fecha de pago.
 */
@SpringBootTest
class StockRecoveryTest {

    @Autowired
    private OrderInventoryService orderInventoryService;
    @Autowired
    private StockRecoveryJob stockRecoveryJob;
    @Autowired
    private StoreRepository storeRepository;
    @Autowired
    private CategoryRepository categoryRepository;
    @Autowired
    private ProductRepository productRepository;
    @Autowired
    private OrderRepository orderRepository;
    @Autowired
    private OrderStatusHistoryRepository historyRepository;
    @Autowired
    private TransactionTemplate transactionTemplate;
    @Autowired
    private StockDecrementListener stockDecrementListener;

    private final List<Long> createdOrders = new ArrayList<>();
    private final List<Product> createdProducts = new ArrayList<>();
    private final List<Category> createdCategories = new ArrayList<>();
    private final List<Store> createdStores = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        // Order matters: order_items references products with ON DELETE RESTRICT. El historial se va
        // con la orden (ON DELETE CASCADE).
        orderRepository.deleteAllById(createdOrders);
        productRepository.deleteAll(createdProducts);
        categoryRepository.deleteAll(createdCategories);
        storeRepository.deleteAll(createdStores);
        createdOrders.clear();
        createdProducts.clear();
        createdCategories.clear();
        createdStores.clear();
    }

    @Test
    @DisplayName("Dos copias viejas de la misma orden aplicadas a la vez descuentan una sola vez")
    void theClaimLetsOnlyOneCallDecrement() throws Exception {
        Store store = store();
        Product product = product(store, 5);
        Long orderId = paidOrderWithoutStock(store, product, 1, Duration.ofHours(1));
        // Las dos leídas ANTES de que nadie descuente: las dos dicen stock_applied = false en memoria,
        // que es lo que veían el listener y el barrido con la guarda anterior. Así el resultado no depende
        // de quién llegue primero.
        Order first = staleCopy(orderId);
        Order second = staleCopy(orderId);
        ConcurrentLinkedQueue<Boolean> results = new ConcurrentLinkedQueue<>();

        runConcurrently(
                () -> results.add(orderInventoryService.applyOrderStock(first)),
                () -> results.add(orderInventoryService.applyOrderStock(second)));

        assertThat(stockOf(product)).as("una venta de 1 unidad descuenta 1").isEqualTo(4);
        assertThat(orderRepository.findById(orderId).orElseThrow().isStockApplied()).isTrue();
        assertThat(results).as("exactamente una llamada aplica el descuento").containsExactlyInAnyOrder(true, false);
    }

    @Test
    @DisplayName("El barrido y el listener a la vez sobre la misma orden: un solo descuento")
    void theSweepAndTheListenerDoNotBothDecrement() throws Exception {
        Store store = store();
        Product product = product(store, 5);
        Long orderId = paidOrderWithoutStock(store, product, 2, Duration.ofHours(1));
        Order listenersCopy = staleCopy(orderId);

        runConcurrently(
                () -> orderInventoryService.applyOrderStock(listenersCopy),
                stockRecoveryJob::recoverMissedStock);

        assertThat(stockOf(product)).isEqualTo(3);
    }

    @Test
    @DisplayName("Una orden pagada sin descontar se recupera, una sola vez y con aviso en el historial")
    void aPaidOrderWithoutStockIsRecoveredOnce() {
        Store store = store();
        Product product = product(store, 10);
        Long orderId = paidOrderWithoutStock(store, product, 3, Duration.ofHours(1));

        stockRecoveryJob.recoverMissedStock();

        assertThat(stockOf(product)).isEqualTo(7);
        assertThat(orderRepository.findById(orderId).orElseThrow().isStockApplied()).isTrue();
        assertThat(notesOf(orderId)).anySatisfy(notes -> assertThat(notes).contains("Stock descontado por el barrido"));

        stockRecoveryJob.recoverMissedStock();

        assertThat(stockOf(product)).as("la segunda corrida no vuelve a descontar").isEqualTo(7);
        assertThat(notesOf(orderId)).filteredOn(notes -> notes.contains("Stock descontado por el barrido")).hasSize(1);
    }

    @Test
    @DisplayName("El barrido solo mira órdenes pagadas, sin descontar y pasado el margen")
    void theSweepOnlyPicksPaidUnappliedOrdersPastTheMargin() {
        Store store = store();
        Product missedProduct = product(store, 10);
        Product recentProduct = product(store, 10);
        Product appliedProduct = product(store, 10);
        Product pendingProduct = product(store, 10);
        Product failedProduct = product(store, 10);

        Long missed = paidOrderWithoutStock(store, missedProduct, 1, Duration.ofHours(1));
        // El listener de un pago de hace un minuto puede estar corriendo todavía: no es una anomalía.
        Long recent = paidOrderWithoutStock(store, recentProduct, 1, Duration.ofMinutes(1));
        Long applied = order(store, appliedProduct, 1, PaymentStatus.PAID, true, Instant.now().minus(Duration.ofHours(1)));
        Long pending = order(store, pendingProduct, 1, PaymentStatus.PENDING, false, null);
        Long failed = order(store, failedProduct, 1, PaymentStatus.FAILED, false, null);

        List<Long> selected = orderRepository
                .findPaidWithoutStockApplied(Instant.now().minus(Duration.ofMinutes(15)), PageRequest.of(0, 500))
                .stream().map(Order::getId).toList();
        assertThat(selected).contains(missed).doesNotContain(recent, applied, pending, failed);

        stockRecoveryJob.recoverMissedStock();

        assertThat(stockOf(missedProduct)).isEqualTo(9);
        assertThat(stockOf(recentProduct)).isEqualTo(10);
        assertThat(stockOf(appliedProduct)).isEqualTo(10);
        assertThat(stockOf(pendingProduct)).isEqualTo(10);
        assertThat(stockOf(failedProduct)).isEqualTo(10);
    }

    @Test
    @DisplayName("Sin stock suficiente, el barrido marca la orden y deja las dos notas sin bajar de cero")
    void aRecoveredOrderWithoutEnoughStockIsFlaggedNotRetried() {
        Store store = store();
        Product product = product(store, 1);
        Long orderId = paidOrderWithoutStock(store, product, 3, Duration.ofHours(1));

        stockRecoveryJob.recoverMissedStock();

        assertThat(stockOf(product)).isEqualTo(1);
        // Marcada igualmente: si no, el barrido la reintentaría en cada corrida, para siempre.
        assertThat(orderRepository.findById(orderId).orElseThrow().isStockApplied()).isTrue();
        assertThat(notesOf(orderId))
                .anySatisfy(notes -> assertThat(notes).contains("Stock insuficiente al confirmar el pago"))
                .anySatisfy(notes -> assertThat(notes).contains("Stock descontado por el barrido"));
    }

    @Test
    @DisplayName("El listener del pago sin stock suficiente deja su nota y no deshace las otras líneas")
    void theListenerKeepsItsNoteAndOtherLinesWhenStockIsShort() {
        // El fallo que destapó el caso anterior, en su sitio de origen. El listener lee la orden con
        // findById, así que su historial no está cargado; el descuento limpia el contexto y añadir la nota
        // a la colección lanzaba LazyInitializationException. La transacción entera se deshacía: sin nota,
        // sin stock_applied y devolviendo también la línea que sí se había descontado.
        Store store = store();
        Product plenty = product(store, 5);
        Product empty = product(store, 0);
        Long orderId = paidOrderWithoutStock(store, plenty, 2, Duration.ofSeconds(1));
        addItem(orderId, empty, 1);

        stockDecrementListener.onPaymentConfirmed(new PaymentConfirmedEvent(orderId));

        assertThat(stockOf(plenty)).as("la línea con stock se descuenta igualmente").isEqualTo(3);
        assertThat(stockOf(empty)).isZero();
        assertThat(orderRepository.findById(orderId).orElseThrow().isStockApplied()).isTrue();
        assertThat(notesOf(orderId)).anySatisfy(notes -> assertThat(notes).contains("Stock insuficiente al confirmar el pago"));
    }

    // --- helpers -----------------------------------------------------------------------------------

    private void addItem(Long orderId, Product product, int quantity) {
        transactionTemplate.executeWithoutResult(status -> {
            Order order = orderRepository.findById(orderId).orElseThrow();
            OrderItem item = new OrderItem();
            item.setOrder(order);
            item.setProduct(product);
            item.setProductName(product.getName());
            item.setProductSku(product.getSku());
            item.setQuantity(quantity);
            item.setPrice(product.getPrice());
            item.setSubtotal(product.getPrice().multiply(BigDecimal.valueOf(quantity)));
            item.setTaxAmount(BigDecimal.ZERO);
            order.getItems().add(item);
        });
    }

    /**
     * Una copia separada de la sesión con lo que {@code applyOrderStock} necesita ya cargado (líneas y
     * productos), porque fuera de una transacción esas relaciones perezosas no se pueden leer.
     */
    private Order staleCopy(Long orderId) {
        return transactionTemplate.execute(status -> {
            Order order = orderRepository.findById(orderId).orElseThrow();
            order.getItems().forEach(item -> Hibernate.initialize(item.getProduct()));
            return order;
        });
    }

    private List<String> notesOf(Long orderId) {
        return historyRepository.findByOrderIdOrderByCreatedAtDesc(orderId).stream()
                .map(h -> h.getNotes() == null ? "" : h.getNotes())
                .toList();
    }

    private int stockOf(Product product) {
        return productRepository.findById(product.getId()).orElseThrow().getStock();
    }

    /** Mismo mecanismo que {@code StockConcurrencyTest.runConcurrently}. */
    private void runConcurrently(Runnable first, Runnable second) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);
        try {
            for (Runnable task : List.of(first, second)) {
                executor.submit(() -> {
                    try {
                        start.await();
                        task.run();
                    } catch (Exception expectedForTheLoser) {
                        // asserted through the final state, not here
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertThat(done.await(30, TimeUnit.SECONDS)).as("ambos hilos deben terminar").isTrue();
        } finally {
            executor.shutdownNow();
        }
    }

    private Store store() {
        Store store = storeRepository.save(Store.builder()
                .name("Recovery Store")
                .slug("recov-" + System.nanoTime())
                .status(StoreStatus.ACTIVE)
                .build());
        createdStores.add(store);
        return store;
    }

    private Product product(Store store, int stock) {
        long seq = System.nanoTime();
        Category category = categoryRepository.save(Category.builder()
                .store(store).name("Recuperación " + seq).slug("recov-cat-" + seq).active(true).build());
        createdCategories.add(category);

        Product product = productRepository.save(Product.builder()
                .store(store).category(category)
                .name("Artículo " + seq).slug("recov-prod-" + seq).sku("RECOV-" + seq)
                .productType(ProductType.SIMPLE)
                .price(BigDecimal.valueOf(1000))
                .stock(stock).manageStock(true).active(true)
                .build());
        createdProducts.add(product);
        return product;
    }

    private Long paidOrderWithoutStock(Store store, Product product, int quantity, Duration paidAgo) {
        return order(store, product, quantity, PaymentStatus.PAID, false, Instant.now().minus(paidAgo));
    }

    private Long order(Store store, Product product, int quantity, PaymentStatus paymentStatus,
                       boolean stockApplied, Instant paidAt) {
        BigDecimal total = product.getPrice().multiply(BigDecimal.valueOf(quantity));
        Order order = new Order();
        order.setStore(store);
        order.setOrderNumber("ORD-RECOV-" + System.nanoTime());
        order.setCustomerEmail("comprador@test.local");
        order.setCustomerFirstName("Test");
        order.setCustomerLastName("Comprador");
        order.setSubtotal(total);
        order.setDiscountAmount(BigDecimal.ZERO);
        order.setShippingCost(BigDecimal.ZERO);
        order.setTaxAmount(BigDecimal.ZERO);
        order.setTotal(total);
        order.setStatus(paymentStatus == PaymentStatus.PAID ? OrderStatus.PROCESSING : OrderStatus.PENDING);
        order.setPaymentStatus(paymentStatus);
        order.setFulfillmentStatus(FulfillmentStatus.UNFULFILLED);
        order.setStockApplied(stockApplied);
        order.setPaidAt(paidAt);

        OrderItem item = new OrderItem();
        item.setOrder(order);
        item.setProduct(product);
        item.setProductName(product.getName());
        item.setProductSku(product.getSku());
        item.setQuantity(quantity);
        item.setPrice(product.getPrice());
        item.setSubtotal(total);
        item.setTaxAmount(BigDecimal.ZERO);

        List<OrderItem> items = new ArrayList<>();
        items.add(item);
        order.setItems(items);

        Long id = orderRepository.save(order).getId();
        createdOrders.add(id);
        return id;
    }
}
