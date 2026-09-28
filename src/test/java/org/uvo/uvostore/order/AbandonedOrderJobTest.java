package org.uvo.uvostore.order;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.uvo.uvostore.entity.order.Order;
import org.uvo.uvostore.entity.order.enums.OrderStatus;
import org.uvo.uvostore.entity.order.enums.PaymentStatus;
import org.uvo.uvostore.repository.OrderRepository;
import org.uvo.uvostore.service.order.AbandonedOrderJob;
import org.uvo.uvostore.service.order.OrderStatusService;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * F15. El job que suelta las reservas de las órdenes que nadie va a pagar.
 *
 * <p>El cupón se reserva al crear la orden y solo se libera si esta se cancela o su pago falla. Una orden
 * abandonada no pasa por ninguna de las dos, así que retenía su uso para siempre — y ni la conciliación la
 * miraba, porque exige un id de pasarela que a estas les falta.
 *
 * <p>Unitario: lo que hay que fijar es <b>a quién cancela y a quién no</b>. Que {@code markCancelled}
 * devuelva el cupón y el stock ya lo cubre {@code OrderInventoryTest}, y que sea idempotente,
 * {@code OrderPaymentTransitionsTest}.
 */
class AbandonedOrderJobTest {

    private final OrderRepository orderRepository = mock(OrderRepository.class);
    private final OrderStatusService orderStatusService = mock(OrderStatusService.class);

    private final AbandonedOrderJob job = new AbandonedOrderJob(orderRepository, orderStatusService, 24, 50);

    @Test
    @DisplayName("Cancela las pendientes que ya pasaron el plazo")
    void itCancelsOrdersPastTheDeadline() {
        Order abandoned = pendingOrder(1L);
        when(orderRepository.findAbandonedPending(any(), any())).thenReturn(List.of(abandoned));

        job.cancelAbandonedOrders();

        // markCancelled es quien libera cupón y stock, y es idempotente: no hace falta duplicar su lógica.
        verify(orderStatusService).markCancelled(1L);
    }

    @Test
    @DisplayName("Si no hay ninguna, no toca nada")
    void itDoesNothingWhenThereAreNone() {
        when(orderRepository.findAbandonedPending(any(), any())).thenReturn(List.of());

        job.cancelAbandonedOrders();

        verify(orderStatusService, never()).markCancelled(any());
    }

    @Test
    @DisplayName("Una orden que revienta no se lleva por delante a las demás de la tanda")
    void oneFailureDoesNotStopTheBatch() {
        // Es el mismo criterio que PosNotificationRetryJob: el lote tiene que terminar.
        Order first = pendingOrder(1L);
        Order second = pendingOrder(2L);
        when(orderRepository.findAbandonedPending(any(), any())).thenReturn(List.of(first, second));
        when(orderStatusService.markCancelled(1L)).thenThrow(new IllegalStateException("boom"));

        job.cancelAbandonedOrders();

        verify(orderStatusService).markCancelled(eq(2L));
    }

    @Test
    @DisplayName("El plazo se pide con la antigüedad configurada")
    void theDeadlineUsesTheConfiguredAge() {
        // Que el corte sea el que se configuró y no otro: con un plazo mal calculado, o no cancela nada o
        // cancela compras legítimas que todavía se están pagando por transferencia.
        when(orderRepository.findAbandonedPending(any(), any())).thenReturn(List.of());
        Instant before = Instant.now().minus(Duration.ofHours(24));

        job.cancelAbandonedOrders();

        org.mockito.ArgumentCaptor<Instant> captor = org.mockito.ArgumentCaptor.forClass(Instant.class);
        verify(orderRepository).findAbandonedPending(captor.capture(), any());
        assertThat(captor.getValue()).isBetween(before.minusSeconds(30), before.plusSeconds(30));
    }

    private Order pendingOrder(long id) {
        Order order = Order.builder()
                .id(id)
                .orderNumber("ORD-ABN-" + id)
                .status(OrderStatus.PENDING)
                .paymentStatus(PaymentStatus.PENDING)
                .build();
        order.setStore(org.uvo.uvostore.entity.tenant.Store.builder().name("T").slug("t").build());
        order.getStore().setId(1L);
        when(orderRepository.findById(id)).thenReturn(Optional.of(order));
        return order;
    }
}
