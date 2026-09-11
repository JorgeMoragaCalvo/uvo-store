package org.uvo.uvostore.payment;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.uvo.uvostore.entity.catalog.Category;
import org.uvo.uvostore.entity.catalog.Product;
import org.uvo.uvostore.entity.order.Order;
import org.uvo.uvostore.entity.order.OrderItem;
import org.uvo.uvostore.entity.order.OrderStatusHistory;
import org.uvo.uvostore.entity.order.enums.FulfillmentStatus;
import org.uvo.uvostore.entity.order.enums.OrderStatus;
import org.uvo.uvostore.entity.order.enums.PaymentMethodType;
import org.uvo.uvostore.entity.order.enums.PaymentStatus;
import org.uvo.uvostore.entity.tenant.Store;
import org.uvo.uvostore.repository.OrderRepository;
import org.uvo.uvostore.repository.OrderStatusHistoryRepository;
import org.uvo.uvostore.service.order.OrderStatusServiceImpl;
import org.uvo.uvostore.support.IntegrationTestSupport;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * G1, la selección de la conciliación. Es la mitad de la que depende que el trabajo sirva de algo y
 * no haga daño: mirar una orden ya pagada sería trabajo inútil, y volver a mirar —y a alertar sobre—
 * una orden con descuadre de monto lo sería para siempre, porque ésa no espera a la pasarela sino a
 * una persona.
 */
class PaymentReconciliationSelectionTest extends IntegrationTestSupport {

    @Autowired
    private OrderRepository orderRepository;
    @Autowired
    private OrderStatusHistoryRepository historyRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("Solo se concilian las órdenes pendientes con identificador en la pasarela")
    void onlyPendingOrdersWithAGatewayIdentifierAreSelected() {
        Store store = createStore("concil");
        Product product = product(store);

        Order pending = order(store, product, PaymentStatus.PENDING, PaymentMethodType.WEBPAY, "tok-1", null, Duration.ofHours(1));
        Order alreadyPaid = order(store, product, PaymentStatus.PAID, PaymentMethodType.WEBPAY, "tok-2", null, Duration.ofHours(1));
        Order neverReachedGateway = order(store, product, PaymentStatus.PENDING, PaymentMethodType.WEBPAY, null, null, Duration.ofHours(1));
        Order stripePending = order(store, product, PaymentStatus.PENDING, PaymentMethodType.STRIPE, null, "cs_test_1", Duration.ofHours(1));
        Order tooRecent = order(store, product, PaymentStatus.PENDING, PaymentMethodType.WEBPAY, "tok-3", null, Duration.ofMinutes(1));

        List<String> selected = select();

        assertThat(selected).contains(pending.getOrderNumber());
        // Stripe no guarda paymentId hasta cobrar; su identificador es la sesión de checkout.
        assertThat(selected).contains(stripePending.getOrderNumber());
        // Ya resuelta: no hay nada que preguntar.
        assertThat(selected).doesNotContain(alreadyPaid.getOrderNumber());
        // Sin identificador no hay contra qué conciliar: el cliente nunca llegó a la pasarela.
        assertThat(selected).doesNotContain(neverReachedGateway.getOrderNumber());
        // Demasiado reciente: el webhook todavía puede llegar por su cuenta.
        assertThat(selected).doesNotContain(tooRecent.getOrderNumber());
    }

    @Test
    @DisplayName("Una orden con nota de descuadre de monto no se vuelve a conciliar")
    void ordersWithAnAmountMismatchNoteAreLeftAlone() {
        Store store = createStore("descuadre");
        Product product = product(store);

        Order mismatched = order(store, product, PaymentStatus.PENDING, PaymentMethodType.WEBPAY, "tok-m", null, Duration.ofHours(1));
        history(mismatched, OrderStatusServiceImpl.AMOUNT_MISMATCH_PREFIX + ": se recibieron 900 y se esperaban 1000");

        Order otherNote = order(store, product, PaymentStatus.PENDING, PaymentMethodType.WEBPAY, "tok-o", null, Duration.ofHours(1));
        history(otherNote, "Orden creada");

        List<String> selected = select();

        // La pasarela ya respondió, y por otro importe: esta orden espera a una persona, no a otra
        // consulta. Sin este filtro, cada corrida la volvería a mirar y a alertar para siempre.
        assertThat(selected).doesNotContain(mismatched.getOrderNumber());
        // Una nota cualquiera no debe excluir nada.
        assertThat(selected).contains(otherNote.getOrderNumber());
    }

    @Test
    @DisplayName("Las más antiguas van primero")
    void theOldestOrdersComeFirst() {
        Store store = createStore("concil-orden");
        Product product = product(store);

        Order newer = order(store, product, PaymentStatus.PENDING, PaymentMethodType.WEBPAY, "tok-n", null, Duration.ofHours(1));
        Order older = order(store, product, PaymentStatus.PENDING, PaymentMethodType.WEBPAY, "tok-v", null, Duration.ofHours(5));

        List<String> selected = select();

        assertThat(selected.indexOf(older.getOrderNumber()))
                .isLessThan(selected.indexOf(newer.getOrderNumber()));
    }

    private List<String> select() {
        return orderRepository.findPendingPaymentsToReconcile(
                        Instant.now().minus(Duration.ofMinutes(15)),
                        OrderStatusServiceImpl.AMOUNT_MISMATCH_PREFIX + "%",
                        PageRequest.of(0, 200))
                .stream().map(Order::getOrderNumber).toList();
    }

    private void history(Order order, String notes) {
        OrderStatusHistory entry = new OrderStatusHistory();
        entry.setOrder(order);
        entry.setStatus(OrderStatus.PENDING.name());
        entry.setNotes(notes);
        historyRepository.saveAndFlush(entry);
    }

    private Product product(Store store) {
        Category category = createCategory(store, "Conciliación");
        return createProduct(store, category, "Producto", BigDecimal.valueOf(1000));
    }

    private Order order(Store store, Product product, PaymentStatus paymentStatus, PaymentMethodType method,
                        String paymentId, String stripeSessionId, Duration age) {
        Order order = new Order();
        order.setStore(store);
        order.setOrderNumber("ORD-CONC-" + nextSeq());
        order.setCustomerEmail("comprador@test.local");
        order.setCustomerFirstName("Test");
        order.setCustomerLastName("Comprador");
        order.setSubtotal(BigDecimal.valueOf(1000));
        order.setDiscountAmount(BigDecimal.ZERO);
        order.setShippingCost(BigDecimal.ZERO);
        order.setTaxAmount(BigDecimal.ZERO);
        order.setTotal(BigDecimal.valueOf(1000));
        order.setStatus(OrderStatus.PENDING);
        order.setPaymentStatus(paymentStatus);
        order.setPaymentMethod(method);
        order.setPaymentId(paymentId);
        order.setStripeCheckoutSessionId(stripeSessionId);
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

        Order saved = orderRepository.saveAndFlush(order);
        // createdAt lo pone @CreationTimestamp, así que la antigüedad hay que forzarla en la base.
        jdbcTemplate.update("UPDATE orders SET created_at = ? WHERE id = ?",
                java.sql.Timestamp.from(Instant.now().minus(age)), saved.getId());
        return saved;
    }
}
