package org.uvo.uvostore.payment;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.uvo.uvostore.entity.order.Coupon;
import org.uvo.uvostore.entity.order.Order;
import org.uvo.uvostore.entity.order.enums.PaymentStatus;
import org.uvo.uvostore.security.TenantContext;
import org.uvo.uvostore.service.BusinessException;
import org.uvo.uvostore.service.payment.RefundCommand;
import org.uvo.uvostore.service.payment.RefundResolution;
import org.uvo.uvostore.service.payment.RefundService;
import org.uvo.uvostore.service.payment.ResolveRefundCommand;
import org.uvo.uvostore.support.AuditRows;
import org.uvo.uvostore.support.InterruptionTestSupport;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * GAP-04. Un reembolso cortado a cada lado de su llamada a Transbank, y la salida que tiene cada caso.
 *
 * <p>El reembolso confirma la intención ({@code PENDING}) en su propia transacción antes de mover un peso (F12), y
 * todo lo demás —el resultado, el stock y el cupón devueltos— en la transacción del reembolso, después. Así que:
 * <ul>
 *   <li><b>Se cae antes de la llamada:</b> queda la intención sin dinero movido. Se resuelve como no hecha, el saldo
 *       se libera y el reembolso se puede pedir otra vez, con otra clave.</li>
 *   <li><b>Se cae después de que Transbank devolvió:</b> queda la intención con el dinero fuera y nada más. El saldo
 *       sigue reservado, nadie puede pedir otro reembolso encima (GAP-04), y resolverla como hecha devuelve el stock
 *       y el cupón una sola vez.</li>
 * </ul>
 */
class RefundInterruptionTest extends InterruptionTestSupport {

    @Autowired
    private RefundService refundService;

    @Test
    @DisplayName("Transbank devolvió y se cayó lo local: el saldo queda reservado, nadie reembolsa otra vez y resolverlo cierra todo una vez")
    void aRefundMadeButNotRecordedIsResolvedOnce() throws Exception {
        Shop shop = shop(10);
        Coupon coupon = coupon(shop);
        Order order = pay(shop, pendingWebpayOrder(shop, 2, coupon));
        assertThat(stock(shop.product())).isEqualTo(8);
        when(TRANSBANK.refund(anyString(), anyString(), anyString(), anyDouble())).thenReturn(refundAccepted());
        doThrow(new IllegalStateException("Caída simulada después de que Transbank devolvió"))
                .doCallRealMethod()
                .when(orderStatusService).markRefunded(eq(order.getId()), anyString());

        assertThatThrownBy(() -> refund(shop, order))
                .hasMessageContaining("Caída simulada");

        // Lo que sobrevive: la intención, con el dinero ya fuera. Nada de lo que hace un reembolso terminado.
        verify(TRANSBANK, times(1)).refund(anyString(), anyString(), anyString(), anyDouble());
        assertThat(refunds(order)).extracting(row -> row.get("status")).containsExactly("PENDING");
        assertThat(reload(order).getPaymentStatus()).isEqualTo(PaymentStatus.PAID);
        assertThat(stock(shop.product())).isEqualTo(8);
        assertThat(timesUsed(coupon)).isEqualTo(1);
        assertThat(couponUsages(order)).isEqualTo(1);
        assertThat(AuditRows.forStore(jdbc, shop.store().getId(), "REFUND_REQUEST")).hasSize(1);
        assertThat(AuditRows.forStore(jdbc, shop.store().getId(), "REFUND_ISSUE")).isEmpty();

        // El operador reintenta: con un pendiente no se pide otro, porque llevaría otra clave y saldría dos veces.
        assertThatThrownBy(() -> refund(shop, order))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("reembolso pendiente");
        verify(TRANSBANK, times(1)).refund(anyString(), anyString(), anyString(), anyDouble());

        // Revisa Transbank y lo confirma: ahora sí vuelven el stock y el cupón.
        Long refundId = pendingRefundId(order);
        resolve(shop, order, refundId, RefundResolution.COMPLETED);

        assertThat(refunds(order)).extracting(row -> row.get("status")).containsExactly("COMPLETED");
        assertThat(reload(order).getPaymentStatus()).isEqualTo(PaymentStatus.REFUNDED);
        assertThat(stock(shop.product())).isEqualTo(10);
        assertThat(timesUsed(coupon)).isZero();
        assertThat(couponUsages(order)).isZero();
        assertThat(AuditRows.forStore(jdbc, shop.store().getId(), "REFUND_RESOLVE")).hasSize(1);

        // Una segunda resolución no devuelve nada otra vez.
        assertThatThrownBy(() -> resolve(shop, order, refundId, RefundResolution.COMPLETED))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("ya está resuelto");
        assertThat(stock(shop.product())).isEqualTo(10);
        assertThat(timesUsed(coupon)).isZero();
    }

    @Test
    @DisplayName("Se cayó tras confirmar la intención y antes de llamar: resuelto como no hecho, el reembolso se repite una vez con otra clave")
    void aRefundLostBeforeTheCallCanBeRequestedAgain() throws Exception {
        Shop shop = shop(10);
        Order order = pay(shop, pendingWebpayOrder(shop, 1, null));
        assertThat(stock(shop.product())).isEqualTo(9);
        crashBeforeTransbank = new IllegalStateException("Caída simulada antes de llamar a Transbank");

        assertThatThrownBy(() -> refund(shop, order))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("queda reservado");

        verify(TRANSBANK, never()).refund(anyString(), anyString(), anyString(), anyDouble());
        assertThat(refunds(order)).extracting(row -> row.get("status")).containsExactly("PENDING");
        assertThat(reload(order).getPaymentStatus()).isEqualTo(PaymentStatus.PAID);
        assertThat(stock(shop.product())).isEqualTo(9);
        assertThatThrownBy(() -> refund(shop, order))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("reembolso pendiente");

        // En Transbank no hay nada: se resuelve como no hecho y el saldo vuelve a estar disponible.
        resolve(shop, order, pendingRefundId(order), RefundResolution.FAILED);
        assertThat(reload(order).getPaymentStatus()).isEqualTo(PaymentStatus.PAID);
        assertThat(stock(shop.product())).isEqualTo(9);

        when(TRANSBANK.refund(anyString(), anyString(), anyString(), anyDouble())).thenReturn(refundAccepted());
        refund(shop, order);

        verify(TRANSBANK, times(1)).refund(anyString(), anyString(), anyString(), anyDouble());
        List<Map<String, Object>> rows = refunds(order);
        assertThat(rows).extracting(row -> row.get("status")).containsExactly("FAILED", "COMPLETED");
        assertThat(rows.get(1).get("idempotency_key")).isNotEqualTo(rows.get(0).get("idempotency_key"));
        assertThat(reload(order).getPaymentStatus()).isEqualTo(PaymentStatus.REFUNDED);
        assertThat(stock(shop.product())).isEqualTo(10);
    }

    private void refund(Shop shop, Order order) {
        TenantContext.runWithin(shop.store(),
                () -> refundService.refund(new RefundCommand(order.getId(), null, "Cliente arrepentido", null)));
    }

    private Long pendingRefundId(Order order) {
        return jdbc.queryForObject("SELECT id FROM order_refunds WHERE order_id = ? AND status = 'PENDING'",
                Long.class, order.getId());
    }

    private void resolve(Shop shop, Order order, Long refundId, RefundResolution outcome) {
        TenantContext.runWithin(shop.store(), () -> refundService.resolve(new ResolveRefundCommand(
                order.getId(), refundId, outcome, "TBK-REF-1", "Revisado en el panel de Transbank")));
    }
}
