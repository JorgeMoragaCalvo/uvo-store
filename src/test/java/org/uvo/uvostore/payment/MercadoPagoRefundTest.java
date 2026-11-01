package org.uvo.uvostore.payment;

import com.mercadopago.client.payment.PaymentRefundClient;
import com.mercadopago.core.MPRequestOptions;
import com.mercadopago.exceptions.MPApiException;
import com.mercadopago.exceptions.MPException;
import com.mercadopago.net.MPResponse;
import com.mercadopago.resources.payment.PaymentRefund;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
import org.uvo.uvostore.service.payment.MercadoPagoServiceImpl;
import org.uvo.uvostore.service.payment.MercadoPagoWebhookSignature;
import org.uvo.uvostore.support.TestPublicUrls;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * GAP-01, la parte de MercadoPago. Qué errores del SDK cuentan como rechazo —{@code BusinessException}, que en
 * {@code RefundService} libera el saldo— y cuáles quedan inciertos porque MercadoPago pudo haber devuelto.
 */
class MercadoPagoRefundTest {

    private static final long PAYMENT_ID = 123456789L;

    private final OrderRepository orderRepository = mock(OrderRepository.class);
    private final PaymentGatewayConfigRepository configRepository = mock(PaymentGatewayConfigRepository.class);
    private final PaymentRefundClient refundClient = mock(PaymentRefundClient.class);

    private final MercadoPagoServiceImpl service = new MercadoPagoServiceImpl(
            orderRepository, configRepository, mock(OrderStatusService.class),
            mock(MercadoPagoWebhookSignature.class), TestPublicUrls.resolver(), 5000, 20000) {
        @Override
        protected PaymentRefundClient refundClient() {
            return refundClient;
        }
    };

    private final PaymentGatewayConfig config = new PaymentGatewayConfig();
    private Order order;

    @BeforeEach
    void setUp() {
        Store store = Store.builder().name("Tienda").slug("tienda").build();
        store.setId(1L);
        TenantContext.set(store);

        order = new Order();
        order.setId(10L);
        order.setStore(store);
        order.setOrderNumber("ORD-10");
        order.setTotal(new BigDecimal("10000.00"));
        order.setPaymentStatus(PaymentStatus.PAID);
        order.setPaymentId(String.valueOf(PAYMENT_ID));
        when(orderRepository.findById(order.getId())).thenReturn(Optional.of(order));

        config.setEnabled(true);
        config.setCredentials(Map.of("accessToken", "TEST-token"));
        when(configRepository.findByStoreIdAndGateway(1L, PaymentGatewayType.MERCADOPAGO)).thenReturn(Optional.of(config));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("Un reembolso aceptado devuelve el id de MercadoPago")
    void anAcceptedRefundReturnsTheGatewayId() throws Exception {
        PaymentRefund refund = mock(PaymentRefund.class);
        when(refund.getId()).thenReturn(77L);
        when(refundClient.refund(eq(PAYMENT_ID), eq(new BigDecimal("10000")), any(MPRequestOptions.class)))
                .thenReturn(refund);

        assertThat(service.refund(order.getId(), new BigDecimal("10000.00"), "refund:10:0:10000")).isEqualTo("77");
    }

    @Test
    @DisplayName("Un 4xx de MercadoPago es un rechazo")
    void aClientErrorIsARejection() throws Exception {
        when(refundClient.refund(any(), any(), any(MPRequestOptions.class))).thenThrow(apiError(400));

        assertThatThrownBy(() -> service.refund(order.getId(), new BigDecimal("10000.00"), "k"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("HTTP 400")
                .hasMessageContaining("amount_exceeded");
    }

    @Test
    @DisplayName("Un 5xx de MercadoPago no dice si la devolución se hizo")
    void aServerErrorIsUncertain() throws Exception {
        when(refundClient.refund(any(), any(), any(MPRequestOptions.class))).thenThrow(apiError(500));

        assertThatThrownBy(() -> service.refund(order.getId(), new BigDecimal("10000.00"), "k"))
                .isInstanceOf(IllegalStateException.class)
                .isNotInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("Un 409 de idempotencia no dice si la devolución se hizo: la petición original puede seguir en curso")
    void anIdempotencyConflictIsUncertain() throws Exception {
        when(refundClient.refund(any(), any(), any(MPRequestOptions.class))).thenThrow(apiError(409));

        assertThatThrownBy(() -> service.refund(order.getId(), new BigDecimal("10000.00"), "k"))
                .isInstanceOf(IllegalStateException.class)
                .isNotInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("Un timeout no dice si la devolución se hizo")
    void aTimeoutIsUncertain() throws Exception {
        when(refundClient.refund(any(), any(), any(MPRequestOptions.class)))
                .thenThrow(new MPException(new java.net.SocketTimeoutException("Read timed out")));

        assertThatThrownBy(() -> service.refund(order.getId(), new BigDecimal("10000.00"), "k"))
                .isInstanceOf(IllegalStateException.class)
                .isNotInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("Sin accessToken es un rechazo, y no se llama a MercadoPago")
    void aMissingAccessTokenIsARejection() throws Exception {
        // Antes se pedía dentro del try y su BusinessException salía convertida en un error incierto: un reembolso
        // que no llegó a salir habría quedado reservando el saldo.
        config.setCredentials(Map.of());

        assertThatThrownBy(() -> service.refund(order.getId(), new BigDecimal("10000.00"), "k"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("accessToken");
        verify(refundClient, never()).refund(any(), any(), any(MPRequestOptions.class));
    }

    private static MPApiException apiError(int status) {
        return new MPApiException("Api error. Check response for details",
                new MPResponse(status, Map.<String, List<String>>of(), "{\"message\":\"amount_exceeded\"}"));
    }
}
