package org.uvo.uvostore.payment;

import cl.transbank.webpay.exception.TransactionCommitException;
import cl.transbank.webpay.exception.TransactionStatusException;
import cl.transbank.webpay.exception.TransbankHttpApiException;
import cl.transbank.webpay.webpayplus.WebpayPlus;
import cl.transbank.webpay.webpayplus.responses.WebpayPlusMallTransactionCreateResponse;
import cl.transbank.webpay.webpayplus.responses.WebpayPlusMallTransactionStatusResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.uvo.uvostore.entity.order.Order;
import org.uvo.uvostore.entity.order.enums.PaymentStatus;
import org.uvo.uvostore.entity.payment.PaymentGatewayConfig;
import org.uvo.uvostore.entity.payment.enums.PaymentGatewayType;
import org.uvo.uvostore.entity.tenant.Store;
import org.uvo.uvostore.repository.OrderRepository;
import org.uvo.uvostore.repository.PaymentGatewayConfigRepository;
import org.uvo.uvostore.security.TenantContext;
import org.uvo.uvostore.service.BusinessException;
import org.uvo.uvostore.service.order.OrderStatusService;
import org.uvo.uvostore.service.payment.PaymentOutcomeUncertainException;
import org.uvo.uvostore.service.payment.PaymentPlatformPolicy;
import org.uvo.uvostore.service.payment.WebpayServiceImpl;
import org.uvo.uvostore.support.TestPublicUrls;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * IMP-07. Lo que la homologación con Transbank dejó a la vista: confirmar un pago puede terminar sin saber si se cobró.
 *
 * <p>Antes cualquier error del {@code commit()} —también un timeout— llevaba al comprador a «el pago fue rechazado» y
 * de vuelta al checkout, donde el reintento abría otra transacción sobre la misma orden. Si Transbank sí había
 * capturado, eran dos cobros, y el token del primero quedaba pisado: nadie podía conciliarlo ni devolverlo.
 *
 * <p>Ahora un 4xx es un «no» (el token venció, se anuló o ya se usó) y todo lo demás es un «no sé», que deja la orden
 * pendiente para la conciliación. Y abrir otra transacción pregunta antes por la anterior.
 */
class WebpayUncertainCommitTest {

    private final OrderRepository orderRepository = mock(OrderRepository.class);
    private final PaymentGatewayConfigRepository configRepository = mock(PaymentGatewayConfigRepository.class);
    private final OrderStatusService orderStatusService = mock(OrderStatusService.class);
    private final WebpayPlus.MallTransaction transbank = mock(WebpayPlus.MallTransaction.class);

    private final WebpayServiceImpl service = new WebpayServiceImpl(
            orderRepository, configRepository, orderStatusService, PaymentPlatformPolicy.of(true),
            "597055555535", "api-key", "integration", TestPublicUrls.resolver(), 20000) {
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
    @DisplayName("Un timeout al confirmar es incierto: la orden no se marca fallida")
    void aTimeoutIsUncertain() throws Exception {
        Order order = pendingOrder("tok-1");
        when(transbank.commit("tok-1")).thenThrow(new IOException("Read timed out"));

        assertThatThrownBy(() -> service.commitTransaction("tok-1"))
                .isInstanceOf(PaymentOutcomeUncertainException.class)
                .satisfies(e -> assertThat(((PaymentOutcomeUncertainException) e).orderNumber()).isEqualTo("ORD-10"));

        verify(orderStatusService, never()).markPaymentFailed(any());
        verify(orderStatusService, never()).markPaid(any(), anyString(), any());
        assertThat(order.getPaymentStatus()).isEqualTo(PaymentStatus.PENDING);
    }

    @Test
    @DisplayName("Un 5xx de Transbank al confirmar también es incierto")
    void aServerErrorIsUncertain() throws Exception {
        pendingOrder("tok-1");
        when(transbank.commit("tok-1")).thenThrow(
                new TransactionCommitException(new TransbankHttpApiException(503, "Service Unavailable")));

        assertThatThrownBy(() -> service.commitTransaction("tok-1"))
                .isInstanceOf(PaymentOutcomeUncertainException.class);
    }

    @Test
    @DisplayName("Un 422 (token abortado, vencido o usado) es un rechazo: Transbank no confirmó")
    void aClientErrorIsARejection() throws Exception {
        pendingOrder("tok-1");
        when(transbank.commit("tok-1")).thenThrow(new TransactionCommitException(
                new TransbankHttpApiException(422, "Transaction has an invalid finished state: aborted")));

        assertThatThrownBy(() -> service.commitTransaction("tok-1"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("422");
    }

    @Test
    @DisplayName("Si el cobro anterior de la orden ya está autorizado, no se abre otra transacción: se marca pagada")
    void aSecondTransactionIsRefusedWhenThePreviousOneCharged() throws Exception {
        Order order = pendingOrder("tok-1");
        webpayEnabled();
        when(transbank.status("tok-1")).thenReturn(statusResponse("AUTHORIZED", (byte) 0, 1000));

        assertThatThrownBy(() -> service.createTransaction(order.getId()))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Esta orden ya fue pagada");

        verify(orderStatusService).markPaid(eq(order.getId()), eq("tok-1"), eq(BigDecimal.valueOf(1000.0)));
        verify(transbank, never()).create(anyString(), anyString(), anyString(), any());
        assertThat(order.getPaymentId()).isEqualTo("tok-1");
    }

    @Test
    @DisplayName("Si la anterior quedó iniciada (el comprador abandonó), se abre otra")
    void aNewTransactionIsOpenedWhenThePreviousOneDidNotCharge() throws Exception {
        Order order = pendingOrder("tok-1");
        webpayEnabled();
        // Así responde Transbank a una transacción autenticada en el banco pero sin confirmar (homologación, IMP-07).
        when(transbank.status("tok-1")).thenReturn(statusResponse("INITIALIZED", (byte) -1, 1000));
        WebpayPlusMallTransactionCreateResponse created = new WebpayPlusMallTransactionCreateResponse();
        created.setToken("tok-2");
        created.setUrl("https://webpay3gint.transbank.cl/webpayserver/initTransaction");
        when(transbank.create(anyString(), anyString(), anyString(), any())).thenReturn(created);

        assertThat(service.createTransaction(order.getId()).token()).isEqualTo("tok-2");

        verify(orderStatusService, never()).markPaid(any(), anyString(), any());
        assertThat(order.getPaymentId()).isEqualTo("tok-2");
    }

    @Test
    @DisplayName("Un token anterior que Transbank ya no conoce (4xx) no impide pagar")
    void anUnknownPreviousTokenDoesNotBlock() throws Exception {
        Order order = pendingOrder("tok-viejo");
        webpayEnabled();
        when(transbank.status("tok-viejo")).thenThrow(
                new TransactionStatusException(new TransbankHttpApiException(422, "Transaction not found")));
        WebpayPlusMallTransactionCreateResponse created = new WebpayPlusMallTransactionCreateResponse();
        created.setToken("tok-2");
        created.setUrl("https://webpay3gint.transbank.cl/webpayserver/initTransaction");
        when(transbank.create(anyString(), anyString(), anyString(), any())).thenReturn(created);

        assertThat(service.createTransaction(order.getId()).token()).isEqualTo("tok-2");
    }

    @Test
    @DisplayName("Sin respuesta sobre el cobro anterior no se abre otra: mejor reintentar que cobrar dos veces")
    void noAnswerAboutThePreviousOneBlocks() throws Exception {
        Order order = pendingOrder("tok-1");
        webpayEnabled();
        when(transbank.status("tok-1")).thenThrow(new IOException("connect timed out"));

        assertThatThrownBy(() -> service.createTransaction(order.getId()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("pago anterior");

        verify(transbank, never()).create(anyString(), anyString(), anyString(), any());
    }

    private void webpayEnabled() {
        PaymentGatewayConfig config = new PaymentGatewayConfig();
        config.setGateway(PaymentGatewayType.WEBPAY);
        config.setEnabled(true);
        config.setCredentials(new HashMap<>(Map.of("childCommerceCode", "597055555536")));
        when(configRepository.findByStoreIdAndGateway(1L, PaymentGatewayType.WEBPAY)).thenReturn(Optional.of(config));
    }

    private WebpayPlusMallTransactionStatusResponse statusResponse(String status, byte responseCode, double amount) {
        WebpayPlusMallTransactionStatusResponse response = new WebpayPlusMallTransactionStatusResponse();
        WebpayPlusMallTransactionStatusResponse.Detail detail = response.new Detail();
        detail.setStatus(status);
        detail.setResponseCode(responseCode);
        detail.setAmount(amount);
        response.setDetails(List.of(detail));
        return response;
    }

    private Order pendingOrder(String token) {
        Store store = Store.builder().name("Tienda").slug("tienda").build();
        store.setId(1L);
        TenantContext.set(store);

        Order order = new Order();
        order.setId(10L);
        order.setStore(store);
        order.setOrderNumber("ORD-10");
        order.setTotal(BigDecimal.valueOf(1000));
        order.setPaymentStatus(PaymentStatus.PENDING);
        order.setPaymentId(token);
        when(orderRepository.findById(order.getId())).thenReturn(Optional.of(order));
        when(orderRepository.findByPaymentId(token)).thenReturn(Optional.of(order));
        return order;
    }
}
