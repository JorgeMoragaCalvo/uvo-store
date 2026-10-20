package org.uvo.uvostore.payment;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
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
import org.uvo.uvostore.service.order.OrderStatusService;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F03, la mitad que faltaba (PROD-13b). Dos confirmaciones simultáneas del mismo pago —en Stripe,
 * {@code checkout.session.completed} y {@code payment_intent.succeeded} llegan casi a la vez— tienen que
 * producir UNA transición a PAID.
 *
 * <p>El cerrojo de {@code findByIdForUpdate} ya hacía esperar a la segunda, pero no bastaba: casi todos los
 * llamadores cargan la orden antes, en su misma transacción, y la consulta con cerrojo devolvía esa misma
 * instancia sin refrescarla. La segunda esperaba el cerrojo y, al obtenerlo, seguía viendo PENDING: pasaba la
 * guarda y la orden se confirmaba dos veces — dos documentos tributarios y dos correos.
 *
 * <p>Por eso cada hilo carga la orden ANTES de llamar a {@code markPaid}, como hacen los webhooks, y los dos
 * esperan a haberla cargado antes de seguir: sin eso, el segundo podría leerla ya pagada y el test pasaría
 * sin ejercitar nada. Transacciones confirmadas de verdad, como {@code StockConcurrencyTest}.
 */
@SpringBootTest
class ConcurrentPaymentConfirmationTest {

    @Autowired
    private OrderStatusService orderStatusService;
    @Autowired
    private TransactionTemplate transactionTemplate;
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

    private Store store;
    private Category category;
    private Product product;
    private Long orderId;

    @AfterEach
    void cleanUp() {
        if (orderId != null) {
            orderRepository.deleteById(orderId);
        }
        productRepository.delete(product);
        categoryRepository.delete(category);
        storeRepository.delete(store);
    }

    @Test
    @DisplayName("Dos confirmaciones simultáneas de un pago ya cargado producen una sola transición")
    void twoConcurrentConfirmationsMarkPaidOnce() throws Exception {
        orderId = pendingOrder().getId();
        CountDownLatch bothLoaded = new CountDownLatch(2);
        ConcurrentLinkedQueue<Throwable> failures = new ConcurrentLinkedQueue<>();

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            for (int i = 0; i < 2; i++) {
                String reference = "pi_concurrente_" + i;
                executor.submit(() -> {
                    try {
                        transactionTemplate.executeWithoutResult(status -> {
                            // Como verifyPayment y los webhooks: la orden entra en la sesión antes del cerrojo.
                            Order loaded = orderRepository.findById(orderId).orElseThrow();
                            assertThat(loaded.getPaymentStatus()).isEqualTo(PaymentStatus.PENDING);
                            bothLoaded.countDown();
                            await(bothLoaded);
                            orderStatusService.markPaid(orderId, reference, loaded.getTotal());
                        });
                    } catch (Throwable e) {
                        failures.add(e);
                    }
                });
            }
        } finally {
            executor.shutdown();
            assertThat(executor.awaitTermination(30, TimeUnit.SECONDS)).as("los dos hilos deben terminar").isTrue();
        }

        assertThat(failures).as("la segunda confirmación sale por la guarda, sin error").isEmpty();
        long confirmations = historyRepository.findByOrderIdOrderByCreatedAtDesc(orderId).stream()
                .filter(h -> "Pago confirmado".equals(h.getNotes()))
                .count();
        assertThat(confirmations).as("una sola transición a PAID").isEqualTo(1);
        assertThat(orderRepository.findById(orderId).orElseThrow().getPaymentStatus()).isEqualTo(PaymentStatus.PAID);
    }

    private static void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private Order pendingOrder() {
        long seq = System.nanoTime();
        store = storeRepository.save(Store.builder()
                .name("Concurrent Payment").slug("conc-pay-" + seq).status(StoreStatus.ACTIVE).build());
        category = categoryRepository.save(Category.builder()
                .store(store).name("Pagos " + seq).slug("conc-pay-cat-" + seq).active(true).build());
        product = productRepository.save(Product.builder()
                .store(store).category(category)
                .name("Artículo " + seq).slug("conc-pay-prod-" + seq).sku("CONCPAY-" + seq)
                .productType(ProductType.SIMPLE)
                .price(BigDecimal.valueOf(1000))
                .stock(10).manageStock(true).active(true)
                .build());

        Order order = new Order();
        order.setStore(store);
        order.setOrderNumber("ORD-CONCPAY-" + seq);
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
        item.setProduct(product);
        item.setProductName(product.getName());
        item.setProductSku(product.getSku());
        item.setQuantity(1);
        item.setPrice(product.getPrice());
        item.setSubtotal(product.getPrice());
        item.setTaxAmount(BigDecimal.ZERO);
        List<OrderItem> items = new ArrayList<>();
        items.add(item);
        order.setItems(items);
        return orderRepository.save(order);
    }
}
