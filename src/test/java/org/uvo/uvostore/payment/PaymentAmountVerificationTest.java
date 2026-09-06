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
import org.uvo.uvostore.service.order.OrderStatusService;
import org.uvo.uvostore.support.IntegrationTestSupport;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M4. None of the three gateways compared what was actually paid against what was owed — Stripe,
 * Webpay and MercadoPago each read the payment's *status* and marked the order paid, with the amount
 * sitting unused in the SDK response. The check now lives inside markPaid so no caller can skip it.
 */
class PaymentAmountVerificationTest extends IntegrationTestSupport {

    @Autowired
    private OrderStatusService orderStatusService;
    @Autowired
    private OrderRepository orderRepository;

    @Test
    @DisplayName("Con el monto correcto la orden queda pagada")
    void anExactAmountMarksTheOrderPaid() {
        Order order = pendingOrder(BigDecimal.valueOf(79980));

        orderStatusService.markPaid(order.getId(), "pay_123", BigDecimal.valueOf(79980));

        Order saved = orderRepository.findById(order.getId()).orElseThrow();
        assertThat(saved.getPaymentStatus()).isEqualTo(PaymentStatus.PAID);
        assertThat(saved.getStatus()).isEqualTo(OrderStatus.PROCESSING);
        assertThat(saved.getPaymentId()).isEqualTo("pay_123");
    }

    @Test
    @DisplayName("Los decimales no importan mientras el valor sea el mismo")
    void scaleDifferencesAreNotAMismatch() {
        Order order = pendingOrder(BigDecimal.valueOf(79980));

        orderStatusService.markPaid(order.getId(), "pay_123", new BigDecimal("79980.00"));

        assertThat(orderRepository.findById(order.getId()).orElseThrow().getPaymentStatus())
                .isEqualTo(PaymentStatus.PAID);
    }

    @Test
    @DisplayName("Si se pagó de menos la orden NO queda pagada y deja rastro")
    void underpaymentLeavesTheOrderPending() {
        Order order = pendingOrder(BigDecimal.valueOf(79980));

        orderStatusService.markPaid(order.getId(), "pay_123", BigDecimal.valueOf(9980));

        Order saved = orderRepository.findById(order.getId()).orElseThrow();
        assertThat(saved.getPaymentStatus())
                .as("cobrar de menos no puede marcar la orden como pagada")
                .isEqualTo(PaymentStatus.PENDING);
        assertThat(saved.getStatusHistory())
                .anyMatch(h -> h.getNotes() != null && h.getNotes().contains("Monto pagado no coincide"));
    }

    @Test
    @DisplayName("Si se pagó de más tampoco se acepta en silencio")
    void overpaymentIsAlsoRejected() {
        Order order = pendingOrder(BigDecimal.valueOf(79980));

        orderStatusService.markPaid(order.getId(), "pay_123", BigDecimal.valueOf(799800));

        assertThat(orderRepository.findById(order.getId()).orElseThrow().getPaymentStatus())
                .isEqualTo(PaymentStatus.PENDING);
    }

    @Test
    @DisplayName("Un peso de diferencia basta para rechazar")
    void oneUnitOffIsEnough() {
        Order order = pendingOrder(BigDecimal.valueOf(79980));

        orderStatusService.markPaid(order.getId(), "pay_123", BigDecimal.valueOf(79979));

        assertThat(orderRepository.findById(order.getId()).orElseThrow().getPaymentStatus())
                .isEqualTo(PaymentStatus.PENDING);
    }

    @Test
    @DisplayName("Un monto desconocido se trata como discrepancia, no se acepta por defecto")
    void anUnknownAmountIsTreatedAsAMismatch() {
        Order order = pendingOrder(BigDecimal.valueOf(79980));

        orderStatusService.markPaid(order.getId(), "pay_123", null);

        assertThat(orderRepository.findById(order.getId()).orElseThrow().getPaymentStatus())
                .isEqualTo(PaymentStatus.PENDING);
    }

    private Order pendingOrder(BigDecimal total) {
        Store store = createStore("amount");
        disableShipping(store);
        Product product = createProduct(store, createCategory(store, "Cat"), "Producto", total);

        Order order = new Order();
        order.setStore(store);
        order.setOrderNumber("ORD-AMT-" + nextSeq());
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
