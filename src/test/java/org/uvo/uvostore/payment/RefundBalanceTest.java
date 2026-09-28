package org.uvo.uvostore.payment;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.uvo.uvostore.entity.order.Order;
import org.uvo.uvostore.entity.order.OrderRefund;
import org.uvo.uvostore.entity.order.enums.FulfillmentStatus;
import org.uvo.uvostore.entity.order.enums.OrderStatus;
import org.uvo.uvostore.entity.order.enums.PaymentMethodType;
import org.uvo.uvostore.entity.order.enums.PaymentStatus;
import org.uvo.uvostore.entity.order.enums.RefundStatus;
import org.uvo.uvostore.entity.order.enums.RefundType;
import org.uvo.uvostore.entity.tenant.Store;
import org.uvo.uvostore.repository.OrderRefundRepository;
import org.uvo.uvostore.repository.OrderRepository;
import org.uvo.uvostore.support.IntegrationTestSupport;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F12. Qué cuenta contra el saldo por devolver, contra la consulta de verdad.
 *
 * <p>Esta es la pieza que hace que el arreglo funcione, y no se puede comprobar con el repositorio
 * simulado: una intención {@code PENDING} tiene que morder el saldo —puede que el dinero ya haya salido y
 * nadie lo sepa— y un {@code FAILED} no, porque ahí la pasarela dijo no y no se movió un peso. Si la
 * consulta se equivoca en cualquiera de las dos, o se devuelve dos veces o queda dinero bloqueado para
 * siempre.
 */
class RefundBalanceTest extends IntegrationTestSupport {

    @Autowired
    private OrderRepository orderRepository;
    @Autowired
    private OrderRefundRepository refundRepository;

    @Test
    @DisplayName("Una intención pendiente muerde el saldo; una fallida no")
    void pendingCountsAgainstTheBalanceAndFailedDoesNot() {
        Order order = paidOrder();

        assertThat(refundRepository.totalRefunded(order.getId())).isEqualByComparingTo("0");

        refundRepository.save(refund(order, "3000", RefundStatus.COMPLETED, "k-completed"));
        assertThat(refundRepository.totalRefunded(order.getId())).isEqualByComparingTo("3000");

        // El dinero pudo haber salido: ese importe deja de estar disponible hasta que alguien lo aclare.
        refundRepository.save(refund(order, "2000", RefundStatus.PENDING, "k-pending"));
        assertThat(refundRepository.totalRefunded(order.getId())).isEqualByComparingTo("5000");

        // La pasarela lo rechazó: no se movió nada, así que el saldo vuelve a quedar libre.
        refundRepository.save(refund(order, "4000", RefundStatus.FAILED, "k-failed"));
        assertThat(refundRepository.totalRefunded(order.getId()))
                .as("un rechazo no puede bloquear dinero que nunca salió")
                .isEqualByComparingTo("5000");
    }

    @Test
    @DisplayName("La clave de idempotencia es única: la misma intención no se puede guardar dos veces")
    void theIdempotencyKeyIsUnique() {
        // Es la red que no depende de que la lógica acierte.
        Order order = paidOrder();
        refundRepository.saveAndFlush(refund(order, "1000", RefundStatus.PENDING, "k-repetida"));

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        refundRepository.saveAndFlush(refund(order, "1000", RefundStatus.PENDING, "k-repetida")))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    private OrderRefund refund(Order order, String amount, RefundStatus status, String key) {
        return OrderRefund.builder()
                .order(order)
                .amount(new BigDecimal(amount))
                .type(RefundType.PARTIAL)
                .status(status)
                .idempotencyKey(key)
                .build();
    }

    private Order paidOrder() {
        Store store = createStore("refund-balance");
        Order order = new Order();
        order.setStore(store);
        order.setOrderNumber("ORD-BAL-" + nextSeq());
        order.setCustomerEmail("comprador@test.local");
        order.setCustomerFirstName("Test");
        order.setCustomerLastName("Comprador");
        order.setSubtotal(new BigDecimal("10000"));
        order.setDiscountAmount(BigDecimal.ZERO);
        order.setShippingCost(BigDecimal.ZERO);
        order.setTaxAmount(BigDecimal.ZERO);
        order.setTotal(new BigDecimal("10000"));
        order.setStatus(OrderStatus.PROCESSING);
        order.setPaymentStatus(PaymentStatus.PAID);
        order.setPaymentMethod(PaymentMethodType.WEBPAY);
        order.setFulfillmentStatus(FulfillmentStatus.UNFULFILLED);
        return orderRepository.save(order);
    }
}
