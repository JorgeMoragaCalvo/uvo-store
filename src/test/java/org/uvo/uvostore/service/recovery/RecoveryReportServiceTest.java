package org.uvo.uvostore.service.recovery;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.uvo.uvostore.config.RecoveryModePolicy;
import org.uvo.uvostore.entity.order.Order;
import org.uvo.uvostore.entity.order.OrderRefund;
import org.uvo.uvostore.entity.order.enums.OrderStatus;
import org.uvo.uvostore.entity.order.enums.PaymentMethodType;
import org.uvo.uvostore.entity.order.enums.PaymentStatus;
import org.uvo.uvostore.entity.order.enums.RefundStatus;
import org.uvo.uvostore.entity.tenant.Store;
import org.uvo.uvostore.repository.OrderRefundRepository;
import org.uvo.uvostore.repository.OrderRepository;
import org.uvo.uvostore.repository.OutboxMessageRepository;
import org.uvo.uvostore.service.BusinessException;
import org.uvo.uvostore.service.order.PaymentService;
import org.uvo.uvostore.service.payment.MercadoPagoService;
import org.uvo.uvostore.service.payment.ProviderPaymentView;
import org.uvo.uvostore.service.payment.WebpayService;
import org.uvo.uvostore.service.recovery.RecoveryReport.PaymentCheck;
import org.uvo.uvostore.service.recovery.RecoveryReport.RefundCheck;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * IMP-09. Las clasificaciones del informe de recuperación que no dependen de la base: reembolsos inciertos contra lo
 * que devolvió la pasarela, un reembolso distinto, una pasarela que no contesta, y que no corre fuera del modo.
 */
class RecoveryReportServiceTest {

    private static final AtomicLong SEQ = new AtomicLong(1);

    private final OrderRepository orderRepository = mock(OrderRepository.class);
    private final OrderRefundRepository refundRepository = mock(OrderRefundRepository.class);
    private final OutboxMessageRepository outboxRepository = mock(OutboxMessageRepository.class);
    private final PaymentService stripe = mock(PaymentService.class);
    private final WebpayService webpay = mock(WebpayService.class);
    private final MercadoPagoService mercadoPago = mock(MercadoPagoService.class);
    private final CredentialDecryptProbe probe = mock(CredentialDecryptProbe.class);
    private final Store store = Store.builder().id(1L).slug("tienda").name("Tienda").build();

    @BeforeEach
    void setUp() {
        when(probe.probe()).thenReturn(new CredentialDecryptProbe.Result(Map.of(), 0));
        when(outboxRepository.findUnfinishedForRecovery(any())).thenReturn(List.of());
        when(refundRepository.findForRecoveryReport(any(), any())).thenReturn(List.of());
        when(orderRepository.findForRecoveryReport(any(), any())).thenReturn(List.of());
        when(refundRepository.findByOrderIdOrderByCreatedAtDesc(anyLong())).thenReturn(List.of());
    }

    @Test
    @DisplayName("Fuera del modo recuperación no pregunta nada a nadie")
    void refusesOutsideRecoveryMode() {
        RecoveryReportService service = service(false);
        assertThatThrownBy(() -> service.report(Instant.now())).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.holdRestoredOutbox(Instant.now())).isInstanceOf(BusinessException.class);
        verifyNoInteractions(webpay, mercadoPago, stripe);
        verify(outboxRepository, never()).holdRestored(any(), any(), any());
    }

    @Test
    @DisplayName("Un reembolso incierto se clasifica por lo que la pasarela devolvió")
    void classifiesPendingRefundsByWhatTheGatewayReturned() {
        Order returned = order(PaymentStatus.PAID);
        Order notReturned = order(PaymentStatus.PAID);
        Order partial = order(PaymentStatus.PAID);
        OrderRefund a = refund(returned, "400", RefundStatus.PENDING);
        OrderRefund b = refund(notReturned, "400", RefundStatus.PENDING);
        OrderRefund c = refund(partial, "400", RefundStatus.PENDING);
        when(refundRepository.findForRecoveryReport(any(), any())).thenReturn(List.of(a, b, c));
        when(webpay.inspect(returned.getId())).thenReturn(view("1000", "400"));
        when(webpay.inspect(notReturned.getId())).thenReturn(view("1000", "0"));
        when(webpay.inspect(partial.getId())).thenReturn(view("1000", "100"));

        RecoveryReport report = service(true).report(Instant.now());

        assertThat(report.stores().get(0).refunds())
                .extracting(RecoveryReport.RefundFinding::classification)
                .containsExactly(RefundCheck.PROVEEDOR_DEVOLVIO, RefundCheck.PROVEEDOR_NO_DEVOLVIO,
                        RefundCheck.NO_CONCLUYENTE);
        assertThat(report.attention()).containsEntry("reembolso.PROVEEDOR_DEVOLVIO", 1L);
    }

    @Test
    @DisplayName("Una devolución que la base no tiene sale como REEMBOLSO_DISTINTO; una pasarela que falla, NO_CONSULTABLE")
    void refundMismatchAndUnreachableGateway() {
        Order refundedThere = order(PaymentStatus.PAID);
        Order unreachable = order(PaymentStatus.PAID);
        when(orderRepository.findForRecoveryReport(any(), any())).thenReturn(List.of(refundedThere, unreachable));
        when(webpay.inspect(refundedThere.getId())).thenReturn(view("1000", "1000"));
        when(webpay.inspect(unreachable.getId())).thenThrow(new IllegalStateException("Transbank no responde"));

        RecoveryReport report = service(true).report(Instant.now());

        assertThat(report.stores().get(0).payments())
                .extracting(RecoveryReport.PaymentFinding::classification)
                .containsExactly(PaymentCheck.REEMBOLSO_DISTINTO, PaymentCheck.NO_CONSULTABLE);
        assertThat(report.stores().get(0).payments().get(1).detail()).isEqualTo("Transbank no responde");
    }

    private RecoveryReportService service(boolean recovery) {
        return new RecoveryReportService(new RecoveryModePolicy(recovery), orderRepository, refundRepository,
                outboxRepository, stripe, webpay, mercadoPago, probe);
    }

    private Order order(PaymentStatus paymentStatus) {
        Order order = new Order();
        order.setId(SEQ.incrementAndGet());
        order.setStore(store);
        order.setOrderNumber("ORD-" + order.getId());
        order.setTotal(new BigDecimal("1000"));
        order.setStatus(OrderStatus.PROCESSING);
        order.setPaymentStatus(paymentStatus);
        order.setPaymentMethod(PaymentMethodType.WEBPAY);
        return order;
    }

    private static OrderRefund refund(Order order, String amount, RefundStatus status) {
        return OrderRefund.builder().id(SEQ.incrementAndGet()).order(order).amount(new BigDecimal(amount))
                .status(status).createdAt(Instant.now()).build();
    }

    private static Optional<ProviderPaymentView> view(String amount, String refunded) {
        return Optional.of(new ProviderPaymentView("AUTHORIZED", true, new BigDecimal(amount),
                new BigDecimal(refunded), "tok"));
    }
}
