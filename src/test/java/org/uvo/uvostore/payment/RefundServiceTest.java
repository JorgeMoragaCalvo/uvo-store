package org.uvo.uvostore.payment;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.uvo.uvostore.entity.order.Order;
import org.uvo.uvostore.entity.order.OrderRefund;
import org.uvo.uvostore.entity.order.OrderStatusHistory;
import org.uvo.uvostore.entity.order.enums.OrderStatus;
import org.uvo.uvostore.entity.order.enums.PaymentMethodType;
import org.uvo.uvostore.entity.order.enums.PaymentStatus;
import org.uvo.uvostore.entity.order.enums.RefundType;
import org.uvo.uvostore.entity.tenant.Store;
import org.uvo.uvostore.repository.OrderRefundRepository;
import org.uvo.uvostore.repository.OrderRepository;
import org.uvo.uvostore.repository.OrderStatusHistoryRepository;
import org.uvo.uvostore.repository.UserRepository;
import org.uvo.uvostore.security.TenantContext;
import org.uvo.uvostore.service.BusinessException;
import org.uvo.uvostore.service.order.OrderStatusService;
import org.uvo.uvostore.service.order.PaymentService;
import org.uvo.uvostore.service.payment.MercadoPagoService;
import org.uvo.uvostore.service.payment.RefundCommand;
import org.uvo.uvostore.service.payment.RefundService;
import org.uvo.uvostore.service.payment.WebpayService;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * G4. Hasta aquí {@code PaymentStatus.REFUNDED} no tenía nada detrás: el panel marcaba la orden como
 * devuelta, restauraba el inventario y no llamaba a ninguna pasarela. Lo que se fija aquí es el
 * despacho —a quién se le pide el dinero, cuánto, y qué se escribe según el saldo—, que no necesita
 * base de datos.
 */
class RefundServiceTest {

    private static final BigDecimal TOTAL = new BigDecimal("10000.00");

    private final OrderRepository orderRepository = mock(OrderRepository.class);
    private final OrderRefundRepository refundRepository = mock(OrderRefundRepository.class);
    private final OrderStatusHistoryRepository historyRepository = mock(OrderStatusHistoryRepository.class);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final OrderStatusService orderStatusService = mock(OrderStatusService.class);
    private final PaymentService paymentService = mock(PaymentService.class);
    private final WebpayService webpayService = mock(WebpayService.class);
    private final MercadoPagoService mercadoPagoService = mock(MercadoPagoService.class);

    private final RefundService service = new RefundService(orderRepository, refundRepository, historyRepository,
            userRepository, orderStatusService, paymentService, webpayService, mercadoPagoService);

    private Store store;

    @BeforeEach
    void setUp() {
        store = Store.builder().name("Tienda").slug("tienda").build();
        store.setId(1L);
        TenantContext.set(store);
        when(refundRepository.save(any(OrderRefund.class))).thenAnswer(i -> i.getArgument(0));
        when(refundRepository.totalRefunded(any())).thenReturn(BigDecimal.ZERO);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("Sin monto se devuelve todo el saldo, por la pasarela de la orden, y la orden queda cerrada")
    void afullRefundGoesThroughTheOrdersOwnGateway() {
        Order order = paidOrder(PaymentMethodType.WEBPAY);
        when(webpayService.refund(order.getId(), TOTAL)).thenReturn("NULLIFIED");

        OrderRefund refund = service.refund(new RefundCommand(order.getId(), null, "Producto defectuoso", 7L));

        verify(webpayService).refund(order.getId(), TOTAL);
        assertThat(refund.getAmount()).isEqualByComparingTo(TOTAL);
        assertThat(refund.getType()).isEqualTo(RefundType.FULL);
        // El tipo que devuelve Transbank se guarda: REVERSED y NULLIFIED son operaciones distintas.
        assertThat(refund.getGatewayReference()).isEqualTo("NULLIFIED");
        verify(orderStatusService).markRefunded(eq(order.getId()), anyString());
    }

    @Test
    @DisplayName("Cada pasarela se cobra por su lado")
    void eachGatewayIsAskedItsOwnWay() {
        Order stripe = paidOrder(PaymentMethodType.STRIPE);
        service.refund(new RefundCommand(stripe.getId(), null, null, null));
        verify(paymentService).refund(stripe.getId(), TOTAL);

        Order mercadoPago = paidOrder(PaymentMethodType.MERCADOPAGO);
        service.refund(new RefundCommand(mercadoPago.getId(), null, null, null));
        verify(mercadoPagoService).refund(mercadoPago.getId(), TOTAL);
    }

    @Test
    @DisplayName("Un reembolso parcial deja la orden pagada y no devuelve el stock")
    void aPartialRefundLeavesTheOrderPaid() {
        Order order = paidOrder(PaymentMethodType.WEBPAY);

        OrderRefund refund = service.refund(new RefundCommand(order.getId(), new BigDecimal("2500"), null, null));

        assertThat(refund.getType()).isEqualTo(RefundType.PARTIAL);
        verify(webpayService).refund(order.getId(), new BigDecimal("2500.00"));
        // La compra no se deshizo: devolver stock y cupón aquí regalaría unidades que el cliente
        // todavía tiene.
        verify(orderStatusService, never()).markRefunded(any(), anyString());
        verify(historyRepository).save(any(OrderStatusHistory.class));
    }

    @Test
    @DisplayName("Los parciales se acumulan: el que agota el saldo cierra la orden")
    void successivePartialRefundsAddUpAndTheLastOneClosesTheOrder() {
        Order order = paidOrder(PaymentMethodType.WEBPAY);
        // Ya se devolvieron 7.500 de los 10.000.
        when(refundRepository.totalRefunded(order.getId())).thenReturn(new BigDecimal("7500.00"));

        OrderRefund refund = service.refund(new RefundCommand(order.getId(), new BigDecimal("2500"), null, null));

        assertThat(refund.getType()).isEqualTo(RefundType.FULL);
        verify(orderStatusService).markRefunded(eq(order.getId()), anyString());
    }

    @Test
    @DisplayName("No se puede devolver más de lo que queda, ni pedirlo a la pasarela")
    void refundingMoreThanTheRemainingBalanceIsRefused() {
        Order order = paidOrder(PaymentMethodType.WEBPAY);
        when(refundRepository.totalRefunded(order.getId())).thenReturn(new BigDecimal("9000.00"));

        assertThatThrownBy(() -> service.refund(new RefundCommand(order.getId(), new BigDecimal("2000"), null, null)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("quedan");

        // Lo que importa no es solo el error: es que no se le pidió el dinero a la pasarela.
        verifyNoInteractions(webpayService);
        verify(refundRepository, never()).save(any());
    }

    @Test
    @DisplayName("Una orden pagada fuera de una pasarela no se puede reembolsar por aquí")
    void aManualOrderCannotBeRefundedThroughAGateway() {
        Order order = paidOrder(PaymentMethodType.MANUAL);

        assertThatThrownBy(() -> service.refund(new RefundCommand(order.getId(), null, null, null)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("externo");

        verifyNoInteractions(webpayService, paymentService, mercadoPagoService);
    }

    @Test
    @DisplayName("Una orden que no está pagada no se reembolsa: eso incluye la ya devuelta entera")
    void anOrderThatIsNotPaidCannotBeRefunded() {
        Order order = paidOrder(PaymentMethodType.WEBPAY);
        order.setPaymentStatus(PaymentStatus.REFUNDED);

        assertThatThrownBy(() -> service.refund(new RefundCommand(order.getId(), null, null, null)))
                .isInstanceOf(BusinessException.class);

        verifyNoInteractions(webpayService);
    }

    @Test
    @DisplayName("El reembolso externo se registra sin llamar a nadie, y exige motivo")
    void anExternalRefundIsRecordedWithoutCallingTheGateway() {
        Order order = paidOrder(PaymentMethodType.WEBPAY);

        assertThatThrownBy(() -> service.recordExternal(new RefundCommand(order.getId(), null, "  ", null)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("motivo");

        OrderRefund refund = service.recordExternal(
                new RefundCommand(order.getId(), null, "Devuelto por transferencia el 3/9", 7L));

        assertThat(refund.getType()).isEqualTo(RefundType.EXTERNAL);
        assertThat(refund.getGatewayReference()).isNull();
        verifyNoInteractions(webpayService, paymentService, mercadoPagoService);
        verify(orderStatusService).markRefunded(eq(order.getId()), anyString());
    }

    @Test
    @DisplayName("Un reembolso externo parcial NO cierra la orden")
    void aPartialExternalRefundDoesNotCloseTheOrder() {
        Order order = paidOrder(PaymentMethodType.WEBPAY);

        OrderRefund refund = service.recordExternal(
                new RefundCommand(order.getId(), new BigDecimal("1000"), "Devolución parcial acordada", null));

        // El tipo es EXTERNAL lo cubra todo o no, así que el cierre no puede decidirse por el tipo:
        // se decide por el saldo. Cerrarla aquí devolvería el stock de una compra que el cliente
        // conserva casi entera.
        assertThat(refund.getType()).isEqualTo(RefundType.EXTERNAL);
        verify(orderStatusService, never()).markRefunded(any(), anyString());
        verify(historyRepository).save(any(OrderStatusHistory.class));
    }

    private Order paidOrder(PaymentMethodType method) {
        long id = method.ordinal() + 100L;
        Order order = new Order();
        order.setId(id);
        order.setStore(store);
        order.setOrderNumber("ORD-" + id);
        order.setTotal(TOTAL);
        order.setStatus(OrderStatus.PROCESSING);
        order.setPaymentStatus(PaymentStatus.PAID);
        order.setPaymentMethod(method);
        order.setPaymentId("ref-" + id);
        when(orderRepository.findByIdForUpdate(id)).thenReturn(Optional.of(order));
        return order;
    }
}
