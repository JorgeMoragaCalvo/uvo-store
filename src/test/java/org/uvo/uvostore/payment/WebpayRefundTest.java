package org.uvo.uvostore.payment;

import cl.transbank.webpay.webpayplus.WebpayPlus;
import cl.transbank.webpay.webpayplus.responses.WebpayPlusMallTransactionRefundResponse;
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
import org.uvo.uvostore.service.payment.WebpayServiceImpl;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * G4, la parte de Webpay. El reembolso de Transbank recibe tres Strings seguidos —token, buyOrder y
 * childCommerceCode— y el compilador no distingue uno de otro: invertir los dos últimos compila
 * perfectamente y falla en producción, contra la pasarela, con dinero de por medio. Este test fija
 * ese orden, y de paso que el monto va en pesos enteros.
 */
class WebpayRefundTest {

    private static final String TOKEN = "tok-1";
    private static final String CHILD_COMMERCE_CODE = "597055555536";

    private final OrderRepository orderRepository = mock(OrderRepository.class);
    private final PaymentGatewayConfigRepository configRepository = mock(PaymentGatewayConfigRepository.class);
    private final OrderStatusService orderStatusService = mock(OrderStatusService.class);
    private final WebpayPlus.MallTransaction transbank = mock(WebpayPlus.MallTransaction.class);

    private final WebpayServiceImpl service = new WebpayServiceImpl(
            orderRepository, configRepository, orderStatusService,
            "597055555535", "api-key", "integration", "http://localhost:5173", 20000) {
        @Override
        protected WebpayPlus.MallTransaction transaction() {
            return transbank;
        }
    };

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
        order.setPaymentId(TOKEN);
        when(orderRepository.findById(order.getId())).thenReturn(Optional.of(order));

        PaymentGatewayConfig config = new PaymentGatewayConfig();
        config.setEnabled(true);
        config.setCredentials(Map.of("childCommerceCode", CHILD_COMMERCE_CODE));
        when(configRepository.findByStoreIdAndGateway(1L, PaymentGatewayType.WEBPAY)).thenReturn(Optional.of(config));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("El reembolso manda token, número de orden y código de comercio hijo, en ese orden")
    void theRefundSendsTheArgumentsInTransbanksOrder() throws Exception {
        when(transbank.refund(TOKEN, "ORD-10", CHILD_COMMERCE_CODE, 10000d))
                .thenReturn(refundResponse("NULLIFIED", (byte) 0));

        String type = service.refund(order.getId(), new BigDecimal("10000.00"));

        assertThat(type).isEqualTo("NULLIFIED");
        // Explícito, para que invertir buyOrder y childCommerceCode rompa aquí y no en Transbank:
        // ambos son String y el compilador los acepta al revés sin decir nada.
        verify(transbank).refund(TOKEN, "ORD-10", CHILD_COMMERCE_CODE, 10000d);
    }

    @Test
    @DisplayName("El monto va en pesos enteros, como al cobrar")
    void theAmountIsSentInWholePesos() throws Exception {
        when(transbank.refund(TOKEN, "ORD-10", CHILD_COMMERCE_CODE, 2500d))
                .thenReturn(refundResponse("NULLIFIED", (byte) 0));

        service.refund(order.getId(), new BigDecimal("2499.60"));

        // CLP no tiene unidad menor y createTransaction ya redondea igual. Mandar decimales aquí es
        // lo que Transbank rechaza.
        verify(transbank).refund(TOKEN, "ORD-10", CHILD_COMMERCE_CODE, 2500d);
    }

    @Test
    @DisplayName("Un rechazo de Transbank no pasa por bueno")
    void aRejectedRefundIsNotTreatedAsDone() throws Exception {
        when(transbank.refund(TOKEN, "ORD-10", CHILD_COMMERCE_CODE, 10000d))
                .thenReturn(refundResponse("NULLIFIED", (byte) -1));

        assertThatThrownBy(() -> service.refund(order.getId(), new BigDecimal("10000.00")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("rechazó");
    }

    @Test
    @DisplayName("Sin token no hay transacción que devolver")
    void anOrderWithoutATokenCannotBeRefunded() {
        order.setPaymentId(null);

        assertThatThrownBy(() -> service.refund(order.getId(), new BigDecimal("10000.00")))
                .isInstanceOf(BusinessException.class);
    }

    private WebpayPlusMallTransactionRefundResponse refundResponse(String type, byte responseCode) {
        WebpayPlusMallTransactionRefundResponse response = new WebpayPlusMallTransactionRefundResponse();
        response.setType(type);
        response.setResponseCode(responseCode);
        return response;
    }
}
