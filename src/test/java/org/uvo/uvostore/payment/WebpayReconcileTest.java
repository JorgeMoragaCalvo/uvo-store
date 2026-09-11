package org.uvo.uvostore.payment;

import cl.transbank.webpay.webpayplus.WebpayPlus;
import cl.transbank.webpay.webpayplus.responses.WebpayPlusMallTransactionStatusResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.uvo.uvostore.entity.order.Order;
import org.uvo.uvostore.entity.order.enums.PaymentStatus;
import org.uvo.uvostore.entity.tenant.Store;
import org.uvo.uvostore.repository.OrderRepository;
import org.uvo.uvostore.repository.PaymentGatewayConfigRepository;
import org.uvo.uvostore.security.TenantContext;
import org.uvo.uvostore.service.order.OrderStatusService;
import org.uvo.uvostore.service.payment.WebpayServiceImpl;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * G1, la parte de Webpay. Conciliar con Transbank tiene una trampa muy concreta: {@code commit()}
 * sobre el token de una transacción autorizada pero no confirmada <b>le cobra al cliente</b>. Una
 * conciliación que use {@code commit()} en lugar de {@code status()} parece funcionar —la orden
 * queda pagada— y estaría cobrando pagos que el cliente abandonó. Esto lo fija por escrito, sin
 * credenciales reales, sustituyendo el único punto por el que {@code WebpayServiceImpl} habla con
 * Transbank.
 */
class WebpayReconcileTest {

    private final OrderRepository orderRepository = mock(OrderRepository.class);
    private final PaymentGatewayConfigRepository configRepository = mock(PaymentGatewayConfigRepository.class);
    private final OrderStatusService orderStatusService = mock(OrderStatusService.class);
    private final WebpayPlus.MallTransaction transbank = mock(WebpayPlus.MallTransaction.class);

    private final WebpayServiceImpl service = new WebpayServiceImpl(
            orderRepository, configRepository, orderStatusService,
            "597055555535", "api-key", "integration", "http://localhost:5173") {
        @Override
        protected WebpayPlus.MallTransaction transaction() {
            return transbank;
        }
    };

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("Conciliar consulta el estado y nunca confirma la transacción")
    void reconcileAsksForTheStatusAndNeverCommits() throws Exception {
        Order order = pendingOrder();
        when(transbank.status("tok-1")).thenReturn(statusResponse("AUTHORIZED", (byte) 0, 1000));
        when(orderStatusService.markPaid(eq(order.getId()), eq("tok-1"), any())).thenAnswer(invocation -> {
            order.setPaymentStatus(PaymentStatus.PAID);
            return order;
        });

        boolean resolved = service.reconcile(order.getId());

        assertThat(resolved).isTrue();
        verify(transbank).status("tok-1");
        // Lo que este test existe para impedir: confirmar aquí le cobraría al cliente que abandonó.
        verify(transbank, never()).commit(anyString());
    }

    @Test
    @DisplayName("Una transacción que Transbank no da por autorizada no se marca como pagada")
    void anUnauthorizedTransactionIsLeftPending() throws Exception {
        Order order = pendingOrder();
        when(transbank.status("tok-1")).thenReturn(statusResponse("FAILED", (byte) -1, 1000));

        boolean resolved = service.reconcile(order.getId());

        assertThat(resolved).isFalse();
        verify(orderStatusService, never()).markPaid(any(), anyString(), any());
        verify(transbank, never()).commit(anyString());
    }

    @Test
    @DisplayName("Sin token no hay nada que consultar")
    void anOrderWithoutATokenIsSkipped() throws Exception {
        Order order = pendingOrder();
        order.setPaymentId(null);

        boolean resolved = service.reconcile(order.getId());

        assertThat(resolved).isFalse();
        verify(transbank, never()).status(anyString());
    }

    private WebpayPlusMallTransactionStatusResponse statusResponse(String status, byte responseCode, double amount) {
        WebpayPlusMallTransactionStatusResponse response = new WebpayPlusMallTransactionStatusResponse();
        // Detail es una clase interna no estática del SDK: solo se puede instanciar desde una
        // respuesta ya creada.
        WebpayPlusMallTransactionStatusResponse.Detail detail = response.new Detail();
        detail.setStatus(status);
        detail.setResponseCode(responseCode);
        detail.setAmount(amount);
        response.setDetails(List.of(detail));
        return response;
    }

    private Order pendingOrder() {
        Store store = Store.builder().name("Tienda").slug("tienda").build();
        store.setId(1L);
        TenantContext.set(store);

        Order order = new Order();
        order.setId(10L);
        order.setStore(store);
        order.setOrderNumber("ORD-10");
        order.setTotal(BigDecimal.valueOf(1000));
        order.setPaymentStatus(PaymentStatus.PENDING);
        order.setPaymentId("tok-1");
        when(orderRepository.findById(order.getId())).thenReturn(Optional.of(order));
        return order;
    }
}
