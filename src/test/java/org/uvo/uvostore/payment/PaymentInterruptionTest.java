package org.uvo.uvostore.payment;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.uvo.uvostore.entity.order.Coupon;
import org.uvo.uvostore.entity.order.Order;
import org.uvo.uvostore.entity.order.enums.OrderStatus;
import org.uvo.uvostore.entity.order.enums.PaymentStatus;
import org.uvo.uvostore.entity.outbox.OutboxMessage;
import org.uvo.uvostore.entity.outbox.enums.OutboxChannel;
import org.uvo.uvostore.entity.outbox.enums.OutboxStatus;
import org.uvo.uvostore.entity.outbox.enums.OutboxType;
import org.uvo.uvostore.security.TenantContext;
import org.uvo.uvostore.service.order.OrderStatusServiceImpl;
import org.uvo.uvostore.service.payment.WebpayCommitResult;
import org.uvo.uvostore.service.pos.PosDelivery;
import org.uvo.uvostore.support.InterruptionTestSupport;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * GAP-04. La confirmación de un pago cortada en cada uno de sus dos commits, y lo que la recoge al volver.
 *
 * <ol>
 *   <li><b>Transbank capturó y se cae antes del commit de la orden</b> (C1: {@code markPaid}, outbox y evento): no
 *       queda nada de la confirmación, y la conciliación, que pregunta a Transbank con {@code status()}, la cobra una
 *       sola vez.</li>
 *   <li><b>Se cae después de C1 y antes del descuento de stock</b> (C2, el listener {@code AFTER_COMMIT}): la orden
 *       queda pagada sin descontar, y lo descuenta una vez el barrido.</li>
 *   <li><b>La confirmación llega cuando la orden ya se canceló</b> (la conciliación no lo logró a tiempo y la canceló
 *       {@code AbandonedOrderJob}): el cobro ya no reactiva la orden, pero queda anotado y avisado, una vez.</li>
 * </ol>
 *
 * <p>En todos se mide lo mismo: cuántas veces se llamó a Transbank y al POS, el stock exacto, el cupón y el outbox.
 */
class PaymentInterruptionTest extends InterruptionTestSupport {

    @Test
    @DisplayName("Transbank capturó y se cayó antes del commit: la conciliación cobra una sola vez, con su stock y su POS")
    void aCapturedPaymentLostBeforeTheCommitIsReconciledOnce() throws Exception {
        Shop shop = shop(10);
        Coupon coupon = coupon(shop);
        Order order = pendingWebpayOrder(shop, 2, coupon);
        when(TRANSBANK.commit(order.getPaymentId())).thenReturn(authorizedCommit(order.getTotal()));
        org.mockito.Mockito.doThrow(new IllegalStateException("Caída simulada antes del commit"))
                .doCallRealMethod()
                .when(orderStatusService).markPaid(eq(order.getId()), anyString(), any());

        assertThatThrownBy(() -> TenantContext.runWithin(shop.store(),
                () -> webpayService.commitTransaction(order.getPaymentId())))
                .hasMessageContaining("Caída simulada");

        // Lo que sobrevive: la orden pendiente con su token, y nada de la confirmación.
        Order lost = reload(order);
        assertThat(lost.getPaymentStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(lost.getPaymentId()).isEqualTo(order.getPaymentId());
        assertThat(outbox(order)).isEmpty();
        assertThat(stock(shop.product())).isEqualTo(10);
        assertThat(timesUsed(coupon)).isEqualTo(1);
        assertThat(couponUsages(order)).isEqualTo(1);

        // Al volver: pasado el margen, la conciliación la elige y le pregunta a Transbank (status, nunca commit).
        backdate(order, "created_at", Duration.ofMinutes(20));
        assertThat(orderRepository.findPendingPaymentsToReconcile(Instant.now().minus(Duration.ofMinutes(15)),
                OrderStatusServiceImpl.AMOUNT_MISMATCH_PREFIX + "%", Pageable.unpaged()))
                .extracting(Order::getId).contains(order.getId());
        when(TRANSBANK.status(order.getPaymentId())).thenReturn(authorizedStatus(order.getTotal()));
        boolean[] resolved = new boolean[1];
        TenantContext.runWithin(shop.store(), () -> resolved[0] = webpayService.reconcile(order.getId()));

        assertThat(resolved[0]).isTrue();
        verify(TRANSBANK, times(1)).commit(order.getPaymentId());
        Order paid = reload(order);
        assertThat(paid.getPaymentStatus()).isEqualTo(PaymentStatus.PAID);
        assertThat(paid.isStockApplied()).isTrue();
        assertThat(stock(shop.product())).isEqualTo(8);
        assertThat(timesUsed(coupon)).as("el cupón se reservó en el checkout y sigue usado una vez").isEqualTo(1);
        assertThat(couponUsages(order)).isEqualTo(1);
        assertThat(outbox(order)).extracting(OutboxMessage::getType)
                .containsExactlyInAnyOrder(OutboxType.POS_ORDER, OutboxType.ORDER_CONFIRMATION_EMAIL);
        assertThat(notes(order)).filteredOn("Pago confirmado"::equals).hasSize(1);

        // La venta llega al POS una vez.
        when(posClient.notifyOrder(any(), any())).thenReturn(PosDelivery.sent(Map.of()));
        dispatcher(shop).dispatch(OutboxChannel.POS);
        dispatcher(shop).dispatch(OutboxChannel.POS);
        verify(posClient, times(1)).notifyOrder(any(), any());

        // Y la confirmación que llega otra vez (el webhook repetido, otra corrida) no duplica nada.
        TenantContext.runWithin(shop.store(), () -> resolved[0] = webpayService.reconcile(order.getId()));
        tx.executeWithoutResult(status -> TenantContext.runWithin(shop.store(),
                () -> orderStatusService.markPaid(order.getId(), order.getPaymentId(), order.getTotal())));
        assertThat(resolved[0]).isFalse();
        assertThat(stock(shop.product())).isEqualTo(8);
        assertThat(outbox(order)).hasSize(2);
        assertThat(notes(order)).filteredOn("Pago confirmado"::equals).hasSize(1);
        verify(posClient, times(1)).notifyOrder(any(), any());
    }

    @Test
    @DisplayName("Se cayó entre el commit del pago y el descuento: el barrido descuenta una vez y la venta llega una vez")
    void aPaymentCommittedWithoutItsStockIsSweptOnce() throws Exception {
        Shop shop = shop(10);
        Order order = pendingWebpayOrder(shop, 3, null);
        org.mockito.Mockito.doThrow(new IllegalStateException("Caída simulada antes del descuento"))
                .doCallRealMethod()
                .when(orderInventoryService).applyOrderStock(any());

        pay(shop, order);

        // C1 quedó: pagada, con su outbox. C2 no: el stock no se tocó.
        Order paid = reload(order);
        assertThat(paid.getPaymentStatus()).isEqualTo(PaymentStatus.PAID);
        assertThat(paid.isStockApplied()).isFalse();
        assertThat(stock(shop.product())).isEqualTo(10);
        assertThat(outbox(order)).hasSize(2);

        // Al volver: pasado el margen, el barrido la recoge. Una segunda pasada no vuelve a descontar.
        backdate(order, "paid_at", Duration.ofMinutes(20));
        stockRecoveryJob(shop).recoverMissedStock();
        stockRecoveryJob(shop).recoverMissedStock();

        assertThat(stock(shop.product())).isEqualTo(7);
        assertThat(reload(order).isStockApplied()).isTrue();
        assertThat(notes(order)).filteredOn(note -> note.startsWith("Stock descontado por el barrido")).hasSize(1);

        when(posClient.notifyOrder(any(), any())).thenReturn(PosDelivery.sent(Map.of()));
        dispatcher(shop).dispatch(OutboxChannel.POS);
        verify(posClient, times(1)).notifyOrder(any(), any());
        assertThat(outbox(order)).filteredOn(row -> row.getType() == OutboxType.POS_ORDER)
                .singleElement().extracting(OutboxMessage::getStatus).isEqualTo(OutboxStatus.SENT);
    }

    @Test
    @DisplayName("Un cobro que llega con la orden ya cancelada no la reactiva, pero queda anotado y avisado una vez")
    void aPaymentOnAnAbandonedOrderIsReportedOnce() throws Exception {
        Shop shop = shop(10);
        Coupon coupon = coupon(shop);
        Order order = pendingWebpayOrder(shop, 1, coupon);

        // La conciliación no lo resolvió en 24 h y el job la canceló: cupón devuelto, nada de stock que devolver.
        backdate(order, "created_at", Duration.ofHours(25));
        abandonedOrderJob(shop).cancelAbandonedOrders();
        assertThat(reload(order).getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(timesUsed(coupon)).isZero();

        // El comprador vuelve de Transbank: el dinero ya se capturó.
        when(TRANSBANK.commit(order.getPaymentId())).thenReturn(authorizedCommit(order.getTotal()));
        WebpayCommitResult[] result = new WebpayCommitResult[1];
        TenantContext.runWithin(shop.store(),
                () -> result[0] = webpayService.commitTransaction(order.getPaymentId()));
        // Y la misma confirmación otra vez (el comprador recarga, o llega por otra vía).
        tx.executeWithoutResult(status -> TenantContext.runWithin(shop.store(),
                () -> orderStatusService.markPaid(order.getId(), order.getPaymentId(), order.getTotal())));

        assertThat(result[0].orderPaymentStatus()).isEqualTo(PaymentStatus.FAILED.name());
        Order closed = reload(order);
        assertThat(closed.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(closed.getPaymentStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(notes(order))
                .filteredOn(note -> note.startsWith(OrderStatusServiceImpl.LATE_PAYMENT_PREFIX))
                .singleElement()
                .satisfies(note -> assertThat(note).contains(order.getPaymentId()).contains("reembólsalo"));
        // Nada de lo que hace un pago: ni venta al POS, ni correo, ni stock, ni cupón.
        assertThat(outbox(order)).isEmpty();
        assertThat(stock(shop.product())).isEqualTo(10);
        assertThat(timesUsed(coupon)).isZero();
        verify(posClient, never()).notifyOrder(any(), any());
    }
}
