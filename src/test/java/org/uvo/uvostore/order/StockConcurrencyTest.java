package org.uvo.uvostore.order;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.uvo.uvostore.entity.catalog.Category;
import org.uvo.uvostore.entity.catalog.Product;
import org.uvo.uvostore.entity.catalog.enums.ProductType;
import org.uvo.uvostore.entity.order.Coupon;
import org.uvo.uvostore.entity.order.Order;
import org.uvo.uvostore.entity.order.OrderItem;
import org.uvo.uvostore.entity.order.enums.CouponType;
import org.uvo.uvostore.entity.order.enums.FulfillmentStatus;
import org.uvo.uvostore.entity.order.enums.OrderStatus;
import org.uvo.uvostore.entity.order.enums.PaymentStatus;
import org.uvo.uvostore.entity.tenant.Store;
import org.uvo.uvostore.repository.CategoryRepository;
import org.uvo.uvostore.repository.CouponRepository;
import org.uvo.uvostore.repository.OrderRepository;
import org.uvo.uvostore.repository.OrderStatusHistoryRepository;
import org.uvo.uvostore.repository.ProductRepository;
import org.uvo.uvostore.repository.StoreRepository;
import org.uvo.uvostore.service.order.CouponService;
import org.uvo.uvostore.service.order.OrderInventoryService;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * C5 — the actual race. Two payments confirmed at the same instant for the last unit in stock, and
 * two checkouts redeeming the same single-use coupon.
 *
 * <p>Deliberately does <b>not</b> extend {@code IntegrationTestSupport}: that base class wraps every
 * test in one transaction that is rolled back at the end, which would isolate the two threads from
 * each other and make the race impossible to reproduce — the test would pass against the old buggy
 * code. Everything here commits for real, so cleanup is manual.
 *
 * <p>These tests fail against the previous read-check-write implementation, which is the only reason
 * they're worth having.
 */
@SpringBootTest
class StockConcurrencyTest {

    @Autowired
    private OrderInventoryService orderInventoryService;
    @Autowired
    private CouponService couponService;
    @Autowired
    private StoreRepository storeRepository;
    @Autowired
    private CategoryRepository categoryRepository;
    @Autowired
    private ProductRepository productRepository;
    @Autowired
    private OrderRepository orderRepository;
    @Autowired
    private CouponRepository couponRepository;
    @Autowired
    private OrderStatusHistoryRepository historyRepository;

    private final List<Order> createdOrders = new ArrayList<>();
    private final List<Coupon> createdCoupons = new ArrayList<>();
    private final List<Product> createdProducts = new ArrayList<>();
    private final List<Category> createdCategories = new ArrayList<>();
    private final List<Store> createdStores = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        // Order matters: order_items references products with ON DELETE RESTRICT.
        orderRepository.deleteAll(createdOrders);
        couponRepository.deleteAll(createdCoupons);
        productRepository.deleteAll(createdProducts);
        categoryRepository.deleteAll(createdCategories);
        storeRepository.deleteAll(createdStores);
        createdOrders.clear();
        createdCoupons.clear();
        createdProducts.clear();
        createdCategories.clear();
        createdStores.clear();
    }

    @Test
    @DisplayName("Dos pagos simultáneos del último artículo: solo uno descuenta, el stock nunca queda negativo")
    void concurrentPaymentsForTheLastUnitDoNotOversell() throws Exception {
        Store store = store();
        Product product = product(store, 1);
        Order first = order(store, product, 1);
        Order second = order(store, product, 1);

        runConcurrently(
                () -> orderInventoryService.applyOrderStock(first),
                () -> orderInventoryService.applyOrderStock(second));

        assertThat(productRepository.findById(product.getId()).orElseThrow().getStock())
                .as("el stock nunca puede quedar negativo")
                .isZero();

        // Read the history through its own repository: there's no transaction around this test, so
        // Order.statusHistory can't be lazily initialised here.
        long ordersWithFailure = List.of(first, second).stream()
                .filter(o -> historyRepository.findByOrderIdOrderByCreatedAtDesc(o.getId()).stream()
                        .anyMatch(h -> h.getNotes() != null && h.getNotes().contains("Stock insuficiente")))
                .count();
        assertThat(ordersWithFailure)
                .as("exactamente una de las dos órdenes debe quedar marcada como no surtible")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("Dos canjes simultáneos de un cupón de un solo uso: solo uno lo obtiene")
    void concurrentCouponClaimsRespectTheLimit() throws Exception {
        Store store = store();
        Coupon coupon = singleUseCoupon(store);
        AtomicInteger claims = new AtomicInteger();

        runConcurrently(
                () -> { if (couponService.claimUsage(coupon)) claims.incrementAndGet(); },
                () -> { if (couponService.claimUsage(coupon)) claims.incrementAndGet(); });

        assertThat(claims.get()).as("solo un canje puede prosperar").isEqualTo(1);
        assertThat(couponRepository.findById(coupon.getId()).orElseThrow().getTimesUsed()).isEqualTo(1);
    }

    /**
     * Releases both tasks from the same latch so they hit the database together — without this they
     * run one after the other and the race never happens. Exceptions are swallowed on purpose: a
     * losing thread is an expected outcome here, and each test asserts on the final state instead.
     */
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
                .name("Concurrency Store")
                .slug("conc-" + System.nanoTime())
                .status("active")
                .build());
        createdStores.add(store);
        return store;
    }

    private Product product(Store store, int stock) {
        long seq = System.nanoTime();
        Category category = categoryRepository.save(Category.builder()
                .store(store).name("Concurrencia " + seq).slug("conc-cat-" + seq).active(true).build());
        createdCategories.add(category);

        Product product = productRepository.save(Product.builder()
                .store(store).category(category)
                .name("Último artículo " + seq).slug("conc-prod-" + seq).sku("CONC-" + seq)
                .productType(ProductType.SIMPLE)
                .price(BigDecimal.valueOf(1000))
                .stock(stock).manageStock(true).active(true)
                .build());
        createdProducts.add(product);
        return product;
    }

    private Coupon singleUseCoupon(Store store) {
        Coupon coupon = new Coupon();
        coupon.setStore(store);
        coupon.setCode("CONC-" + System.nanoTime());
        coupon.setName("Cupón de un solo uso");
        coupon.setType(CouponType.FIXED);
        coupon.setValue(BigDecimal.valueOf(500));
        coupon.setUsageLimit(1);
        coupon.setActive(true);
        Coupon saved = couponRepository.save(coupon);
        createdCoupons.add(saved);
        return saved;
    }

    private Order order(Store store, Product product, int quantity) {
        Order order = new Order();
        order.setStore(store);
        order.setOrderNumber("ORD-CONC-" + System.nanoTime());
        order.setCustomerEmail("comprador@test.local");
        order.setCustomerFirstName("Test");
        order.setCustomerLastName("Comprador");
        order.setSubtotal(BigDecimal.valueOf(1000));
        order.setDiscountAmount(BigDecimal.ZERO);
        order.setShippingCost(BigDecimal.ZERO);
        order.setTaxAmount(BigDecimal.ZERO);
        order.setTotal(BigDecimal.valueOf(1000));
        order.setStatus(OrderStatus.PROCESSING);
        order.setPaymentStatus(PaymentStatus.PAID);
        order.setFulfillmentStatus(FulfillmentStatus.UNFULFILLED);

        OrderItem item = new OrderItem();
        item.setOrder(order);
        item.setProduct(product);
        item.setProductName(product.getName());
        item.setProductSku(product.getSku());
        item.setQuantity(quantity);
        item.setPrice(product.getPrice());
        item.setSubtotal(product.getPrice());
        item.setTaxAmount(BigDecimal.ZERO);

        List<OrderItem> items = new ArrayList<>();
        items.add(item);
        order.setItems(items);

        Order saved = orderRepository.save(order);
        createdOrders.add(saved);
        return saved;
    }
}
