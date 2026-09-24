package org.uvo.uvostore.payment;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.uvo.uvostore.entity.catalog.Product;
import org.uvo.uvostore.entity.order.Order;
import org.uvo.uvostore.entity.order.OrderItem;
import org.uvo.uvostore.entity.order.enums.FulfillmentStatus;
import org.uvo.uvostore.entity.order.enums.OrderStatus;
import org.uvo.uvostore.entity.order.enums.PaymentStatus;
import org.uvo.uvostore.entity.tenant.Store;
import org.uvo.uvostore.repository.OrderRepository;
import org.uvo.uvostore.repository.ProductRepository;
import org.uvo.uvostore.service.order.OrderStatusService;
import org.uvo.uvostore.support.IntegrationTestSupport;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F03. Un pago confirmado no se deshace porque llegue tarde un evento de fallo.
 *
 * <p>El caso real no es una carrera exótica. Los handlers de {@code payment_intent.payment_failed} y
 * {@code payment_intent.canceled} buscan la orden por {@code stripePaymentIntentId}, columna que solo
 * se escribe dentro de {@code markPaid}: sobre una orden pendiente no encuentran nada, así que la
 * única orden que pueden alcanzar es una ya pagada. Con una tarjeta rechazada y un reintento exitoso
 * en la misma sesión, Stripe genera el evento de fallo antes que el de éxito y reintenta su entrega
 * hasta tres días; llegaba con la orden ya PAID y la tumbaba.
 *
 * <p>Lo que se perdía con eso: el dinero cobrado, el cupón liberado, y la orden fuera de la
 * conciliación —que solo mira PENDING— y fuera del reembolso, que exige PAID
 * ({@code RefundService}). Nadie se enteraba.
 */
class OrderPaymentTransitionsTest extends IntegrationTestSupport {

    @Autowired
    private OrderStatusService orderStatusService;
    @Autowired
    private OrderRepository orderRepository;
    @Autowired
    private ProductRepository productRepository;

    @Test
    @DisplayName("Un pago fallido que llega tarde no tumba una orden ya pagada")
    void aLatePaymentFailedDoesNotUndoAPaidOrder() {
        Order order = paidOrder();

        orderStatusService.markPaymentFailed(order.getId());

        Order saved = orderRepository.findById(order.getId()).orElseThrow();
        assertThat(saved.getPaymentStatus()).isEqualTo(PaymentStatus.PAID);
        assertThat(saved.getStatus()).isEqualTo(OrderStatus.PROCESSING);
    }

    @Test
    @DisplayName("Y no lo hace en silencio: queda anotado en el historial de la orden")
    void theIgnoredEventIsRecorded() {
        Order order = paidOrder();

        orderStatusService.markPaymentFailed(order.getId());

        // Si se ignora sin dejar rastro, nadie puede saber que la pasarela dijo algo raro de esta
        // orden. Es el mismo criterio que el descuadre de monto (AMOUNT_MISMATCH_PREFIX).
        assertThat(orderRepository.findById(order.getId()).orElseThrow().getStatusHistory())
                .extracting(h -> h.getNotes() == null ? "" : h.getNotes())
                .anySatisfy(notes -> assertThat(notes).contains("Evento de pago ignorado"));
    }

    @Test
    @DisplayName("Una cancelación tardía no cancela ni devuelve stock de una orden pagada")
    void aLateCancellationDoesNotRestoreStockOfAPaidOrder() {
        Order order = paidOrder();
        int stockAfterSale = productRepository.findById(order.getItems().get(0).getProduct().getId())
                .orElseThrow().getStock();

        orderStatusService.markCancelled(order.getId());

        Order saved = orderRepository.findById(order.getId()).orElseThrow();
        assertThat(saved.getPaymentStatus()).isEqualTo(PaymentStatus.PAID);
        assertThat(saved.getStatus()).isNotEqualTo(OrderStatus.CANCELLED);
        assertThat(productRepository.findById(order.getItems().get(0).getProduct().getId()).orElseThrow().getStock())
                .as("devolver stock de una venta cobrada inventa unidades")
                .isEqualTo(stockAfterSale);
    }

    @Test
    @DisplayName("Una orden reembolsada tampoco vuelve a FAILED")
    void aRefundedOrderDoesNotGoBackToFailed() {
        Order order = paidOrder();
        orderStatusService.markRefunded(order.getId(), "Reembolso de prueba");

        orderStatusService.markPaymentFailed(order.getId());

        assertThat(orderRepository.findById(order.getId()).orElseThrow().getPaymentStatus())
                .isEqualTo(PaymentStatus.REFUNDED);
    }

    @Test
    @DisplayName("El fallo legítimo de una orden pendiente sigue funcionando")
    void aGenuineFailureOnAPendingOrderStillWorks() {
        // El candado no puede haber convertido markPaymentFailed en un no-op: es lo que usa el commit
        // de Webpay cuando Transbank rechaza.
        Order order = pendingOrder();

        orderStatusService.markPaymentFailed(order.getId());

        assertThat(orderRepository.findById(order.getId()).orElseThrow().getPaymentStatus())
                .isEqualTo(PaymentStatus.FAILED);
    }

    @Test
    @DisplayName("Tras el evento tardío la orden sigue siendo reembolsable")
    void theOrderIsStillRefundableAfterTheLateEvent() {
        // Es la consecuencia práctica: RefundService exige PAID, así que una orden mal marcada FAILED
        // no se puede devolver por la vía normal. Este es el caso que lo evita.
        Order order = paidOrder();

        orderStatusService.markPaymentFailed(order.getId());

        assertThat(orderRepository.findById(order.getId()).orElseThrow().getPaymentStatus())
                .as("PAID es lo que RefundService exige para poder devolver el dinero")
                .isEqualTo(PaymentStatus.PAID);
    }

    // --- fixtures ----------------------------------------------------------------------------------

    private Order paidOrder() {
        Order order = pendingOrder();
        orderStatusService.markPaid(order.getId(), "pi_test_" + nextSeq(), order.getTotal());
        Order paid = orderRepository.findById(order.getId()).orElseThrow();
        assertThat(paid.getPaymentStatus()).as("la fixture tiene que quedar pagada").isEqualTo(PaymentStatus.PAID);
        return paid;
    }

    private Order pendingOrder() {
        BigDecimal total = BigDecimal.valueOf(19990);
        Store store = createStore("transitions");
        disableShipping(store);
        Product product = createProduct(store, createCategory(store, "Cat"), "Producto", total);

        Order order = new Order();
        order.setStore(store);
        order.setOrderNumber("ORD-TRS-" + nextSeq());
        order.setCustomerEmail("comprador@test.local");
        order.setCustomerFirstName("Test");
        order.setCustomerLastName("Comprador");
        order.setSubtotal(total);
        order.setDiscountAmount(BigDecimal.ZERO);
        order.setShippingCost(BigDecimal.ZERO);
        order.setTaxAmount(BigDecimal.ZERO);
        order.setTotal(total);
        order.setStatus(OrderStatus.PENDING);
        order.setPaymentStatus(PaymentStatus.PENDING);
        order.setFulfillmentStatus(FulfillmentStatus.UNFULFILLED);

        OrderItem item = new OrderItem();
        item.setOrder(order);
        item.setProduct(product);
        item.setProductName(product.getName());
        item.setProductSku(product.getSku());
        item.setQuantity(1);
        item.setPrice(total);
        item.setSubtotal(total);
        item.setTaxAmount(BigDecimal.ZERO);

        List<OrderItem> items = new ArrayList<>();
        items.add(item);
        order.setItems(items);
        return orderRepository.save(order);
    }
}
