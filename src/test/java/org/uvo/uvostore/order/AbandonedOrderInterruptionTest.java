package org.uvo.uvostore.order;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.uvo.uvostore.entity.order.Coupon;
import org.uvo.uvostore.entity.order.Order;
import org.uvo.uvostore.entity.order.enums.OrderStatus;
import org.uvo.uvostore.support.InterruptionTestSupport;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;

/**
 * GAP-04. El job de órdenes abandonadas cortado a mitad de tanda. Cada orden se cancela en su propia transacción
 * ({@code markCancelled}), así que lo que se cayó no deja nada a medias, lo que ya se canceló no se repite al volver,
 * y el cupón termina devuelto exactamente una vez por orden. {@code AbandonedOrderJobTest} cubre a quién elige; esto,
 * contra la base, lo que queda después.
 */
class AbandonedOrderInterruptionTest extends InterruptionTestSupport {

    @Test
    @DisplayName("Se cayó cancelando la segunda orden de la tanda: al volver se cancela ella, y el cupón vuelve una vez por orden")
    void aBatchCutInTheMiddleFinishesExactlyOnce() {
        Shop shop = shop(10);
        Coupon coupon = coupon(shop);
        Order first = pendingWebpayOrder(shop, 1, coupon);
        Order second = pendingWebpayOrder(shop, 1, coupon);
        backdate(first, "created_at", Duration.ofHours(26));
        backdate(second, "created_at", Duration.ofHours(25));
        assertThat(timesUsed(coupon)).isEqualTo(2);
        doThrow(new IllegalStateException("Caída simulada cancelando la segunda orden"))
                .doCallRealMethod()
                .when(orderStatusService).markCancelled(second.getId());

        abandonedOrderJob(shop).cancelAbandonedOrders();

        // La primera quedó cancelada y con su cupón devuelto; la segunda, intacta.
        assertThat(reload(first).getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(reload(second).getStatus()).isEqualTo(OrderStatus.PENDING);
        assertThat(timesUsed(coupon)).isEqualTo(1);
        assertThat(couponUsages(first)).isZero();
        assertThat(couponUsages(second)).isEqualTo(1);

        // Al volver, y otra vez por si acaso: la segunda se cancela y la primera no se toca.
        abandonedOrderJob(shop).cancelAbandonedOrders();
        abandonedOrderJob(shop).cancelAbandonedOrders();

        assertThat(reload(second).getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(timesUsed(coupon)).isZero();
        assertThat(couponUsages(second)).isZero();
        assertThat(notes(first)).filteredOn(note -> note.startsWith("Sesión de pago expirada")).hasSize(1);
        assertThat(notes(second)).filteredOn(note -> note.startsWith("Sesión de pago expirada")).hasSize(1);
        // Nunca se pagaron: no había stock que devolver.
        assertThat(stock(shop.product())).isEqualTo(10);
    }
}
