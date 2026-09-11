package org.uvo.uvostore.pos;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.uvo.uvostore.entity.catalog.Category;
import org.uvo.uvostore.entity.catalog.Product;
import org.uvo.uvostore.entity.order.Order;
import org.uvo.uvostore.entity.order.OrderItem;
import org.uvo.uvostore.entity.order.enums.FulfillmentStatus;
import org.uvo.uvostore.entity.order.enums.OrderStatus;
import org.uvo.uvostore.entity.order.enums.PaymentStatus;
import org.uvo.uvostore.entity.tenant.Store;
import org.uvo.uvostore.repository.OrderRepository;
import org.uvo.uvostore.support.IntegrationTestSupport;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * G2, la selección del job de reintento. Es la mitad de la que depende que el reintento sirva de
 * algo y no haga daño: reintentar una orden ya sincronizada emitiría un documento tributario
 * duplicado, y reintentar una que nunca tuvo nada que notificar sería trabajo perpetuo.
 */
class PosNotificationRetrySelectionTest extends IntegrationTestSupport {

    private static final int MAX_ATTEMPTS = 5;

    @Autowired
    private OrderRepository orderRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("Solo se reintentan las órdenes que fallaron: ni las sincronizadas, ni las que nunca lo intentaron")
    void onlyOrdersThatTriedAndFailedAreRetried() {
        Store store = createStore("retry");
        Product product = product(store);

        Order failed = order(store, product, false, 1, Duration.ofHours(1));
        Order alreadySynced = order(store, product, true, 1, Duration.ofHours(1));
        Order nothingToSync = order(store, product, false, 0, Duration.ofHours(1));
        Order exhausted = order(store, product, false, MAX_ATTEMPTS, Duration.ofHours(1));
        Order tooRecent = order(store, product, false, 1, Duration.ofMinutes(1));

        List<String> selected = select();

        assertThat(selected).contains(failed.getOrderNumber());
        // Ya notificada: reintentarla duplicaría el documento.
        assertThat(selected).doesNotContain(alreadySynced.getOrderNumber());
        // syncAttempts=0 significa que no había nada que notificar, no que fallara.
        assertThat(selected).doesNotContain(nothingToSync.getOrderNumber());
        // Agotó el tope: sigue mal, pero ya no se reintenta sola — hace falta una persona.
        assertThat(selected).doesNotContain(exhausted.getOrderNumber());
        // Demasiado reciente: puede estar aún en curso.
        assertThat(selected).doesNotContain(tooRecent.getOrderNumber());
    }

    @Test
    @DisplayName("Las más antiguas van primero")
    void theOldestOrdersComeFirst() {
        Store store = createStore("retry-order");
        Product product = product(store);

        Order newer = order(store, product, false, 1, Duration.ofHours(1));
        Order older = order(store, product, false, 1, Duration.ofHours(5));

        List<String> selected = select();

        assertThat(selected.indexOf(older.getOrderNumber()))
                .isLessThan(selected.indexOf(newer.getOrderNumber()));
    }

    private List<String> select() {
        return orderRepository.findPendingPosNotification(
                        MAX_ATTEMPTS, Instant.now().minus(Duration.ofMinutes(10)), PageRequest.of(0, 200))
                .stream().map(Order::getOrderNumber).toList();
    }

    private Product product(Store store) {
        Category category = createCategory(store, "Reintento");
        return createProduct(store, category, "Producto", BigDecimal.valueOf(1000));
    }

    private Order order(Store store, Product product, boolean posSynced, int syncAttempts, Duration age) {
        Order order = new Order();
        order.setStore(store);
        order.setOrderNumber("ORD-RETRY-" + nextSeq());
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
        order.setPosSynced(posSynced);
        order.setSyncAttempts(syncAttempts);

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

        Order saved = orderRepository.saveAndFlush(order);
        // createdAt lo pone @CreationTimestamp, así que la antigüedad hay que forzarla en la base.
        jdbcTemplate.update("UPDATE orders SET created_at = ? WHERE id = ?",
                java.sql.Timestamp.from(Instant.now().minus(age)), saved.getId());
        return saved;
    }
}
