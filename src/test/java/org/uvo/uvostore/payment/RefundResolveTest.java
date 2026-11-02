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
import org.uvo.uvostore.entity.order.enums.RefundStatus;
import org.uvo.uvostore.entity.order.enums.RefundType;
import org.uvo.uvostore.entity.tenant.Store;
import org.uvo.uvostore.repository.OrderRefundRepository;
import org.uvo.uvostore.repository.OrderRepository;
import org.uvo.uvostore.repository.OrderStatusHistoryRepository;
import org.uvo.uvostore.repository.UserRepository;
import org.uvo.uvostore.security.TenantContext;
import org.uvo.uvostore.service.BusinessException;
import org.uvo.uvostore.service.audit.AuditTrail;
import org.uvo.uvostore.service.order.OrderStatusService;
import org.uvo.uvostore.service.order.PaymentService;
import org.uvo.uvostore.service.payment.MercadoPagoService;
import org.uvo.uvostore.service.payment.RefundIntentStore;
import org.uvo.uvostore.service.payment.RefundResolution;
import org.uvo.uvostore.service.payment.RefundService;
import org.uvo.uvostore.service.payment.ResolveRefundCommand;
import org.uvo.uvostore.service.payment.WebpayService;

import java.math.BigDecimal;
import java.util.NoSuchElementException;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * GAP-02. Resolver una intención {@code PENDING} desde el panel, con lo que el operador vio en la pasarela. Hasta
 * aquí la única salida era el {@code UPDATE} a mano de V21. Lo que se fija: no se llama a ninguna pasarela, el saldo
 * queda como corresponde a cada resultado, y no se resuelve dos veces ni sin explicación.
 */
class RefundResolveTest {

    private static final BigDecimal TOTAL = new BigDecimal("10000.00");

    private final OrderRepository orderRepository = mock(OrderRepository.class);
    private final OrderRefundRepository refundRepository = mock(OrderRefundRepository.class);
    private final OrderStatusHistoryRepository historyRepository = mock(OrderStatusHistoryRepository.class);
    private final OrderStatusService orderStatusService = mock(OrderStatusService.class);
    private final PaymentService paymentService = mock(PaymentService.class);
    private final WebpayService webpayService = mock(WebpayService.class);
    private final MercadoPagoService mercadoPagoService = mock(MercadoPagoService.class);
    private final RefundIntentStore intentStore = mock(RefundIntentStore.class);

    private final RefundService service = new RefundService(orderRepository, refundRepository, historyRepository,
            mock(UserRepository.class), orderStatusService, paymentService, webpayService, mercadoPagoService,
            intentStore, mock(AuditTrail.class));

    private Store store;
    private Order order;

    @BeforeEach
    void setUp() {
        store = Store.builder().name("Tienda").slug("tienda").build();
        store.setId(1L);
        TenantContext.set(store);

        order = new Order();
        order.setId(10L);
        order.setStore(store);
        order.setOrderNumber("ORD-10");
        order.setTotal(TOTAL);
        order.setStatus(OrderStatus.PROCESSING);
        order.setPaymentStatus(PaymentStatus.PAID);
        order.setPaymentMethod(PaymentMethodType.WEBPAY);
        when(orderRepository.findByIdForUpdate(order.getId())).thenReturn(Optional.of(order));
        when(refundRepository.save(any(OrderRefund.class))).thenAnswer(i -> i.getArgument(0));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        verifyNoInteractions(paymentService, webpayService, mercadoPagoService, intentStore);
    }

    @Test
    @DisplayName("Confirmado como hecho: queda COMPLETED con la referencia, y un parcial no cierra la orden")
    void aConfirmedRefundIsCompletedWithItsReference() {
        OrderRefund pending = pending(70L, "4000");
        when(refundRepository.totalRefunded(order.getId())).thenReturn(new BigDecimal("4000"));

        OrderRefund resolved = service.resolve(command(70L, RefundResolution.COMPLETED, " 1.23-abc ", "Visto en Transbank"));

        assertThat(resolved.getStatus()).isEqualTo(RefundStatus.COMPLETED);
        assertThat(resolved.getGatewayReference()).isEqualTo("1.23-abc");
        assertThat(pending.getStatus()).isEqualTo(RefundStatus.COMPLETED);
        verify(orderStatusService, never()).markRefunded(any(), anyString());
        verify(historyRepository).save(argThat((OrderStatusHistory h) ->
                h.getNotes().contains("confirmado como hecho") && h.getNotes().contains("Visto en Transbank")));
    }

    @Test
    @DisplayName("Confirmado como hecho, si con él no queda saldo, la compra se deshace")
    void aConfirmedRefundThatExhaustsTheBalanceClosesTheOrder() {
        pending(70L, "10000");
        // El importe pendiente ya contaba en el saldo: confirmarlo no lo cambia.
        when(refundRepository.totalRefunded(order.getId())).thenReturn(new BigDecimal("10000"));

        service.resolve(command(70L, RefundResolution.COMPLETED, "NULLIFIED", "Visto en Transbank"));

        verify(orderStatusService).markRefunded(eq(order.getId()), anyString());
    }

    @Test
    @DisplayName("Confirmado como no hecho: queda FAILED y el importe vuelve a estar disponible")
    void aRefundThatDidNotHappenReleasesTheBalance() {
        OrderRefund pending = pending(70L, "10000");

        OrderRefund resolved = service.resolve(command(70L, RefundResolution.FAILED, null, "No aparece en Transbank"));

        assertThat(resolved.getStatus()).isEqualTo(RefundStatus.FAILED);
        assertThat(pending.getGatewayReference()).isNull();
        verify(orderStatusService, never()).markRefunded(any(), anyString());
        verify(historyRepository).save(argThat((OrderStatusHistory h) -> h.getNotes().contains("vuelve a estar disponible")));
    }

    @Test
    @DisplayName("Sin nota no se resuelve, y sin referencia no se confirma como hecho")
    void aNoteIsRequiredAndAReferenceToConfirm() {
        OrderRefund pending = pending(70L, "10000");

        assertThatThrownBy(() -> service.resolve(command(70L, RefundResolution.FAILED, null, "  ")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("nota");
        assertThatThrownBy(() -> service.resolve(command(70L, RefundResolution.COMPLETED, " ", "Visto en Transbank")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("referencia");

        assertThat(pending.getStatus()).isEqualTo(RefundStatus.PENDING);
        verify(refundRepository, never()).save(any());
    }

    @Test
    @DisplayName("Lo que ya está resuelto no se vuelve a resolver")
    void aResolvedRefundCannotBeResolvedAgain() {
        OrderRefund failed = pending(70L, "10000");
        failed.setStatus(RefundStatus.FAILED);

        // Pasar un FAILED a COMPLETED volvería a morder un saldo que quizá ya se usó en otro reembolso.
        assertThatThrownBy(() -> service.resolve(command(70L, RefundResolution.COMPLETED, "ref", "Visto")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("ya está resuelto");
        verify(refundRepository, never()).save(any());
    }

    @Test
    @DisplayName("Una intención de otra orden no se encuentra")
    void anIntentFromAnotherOrderIsNotFound() {
        Order other = new Order();
        other.setId(11L);
        other.setStore(store);
        OrderRefund foreign = OrderRefund.builder().order(other).amount(TOTAL).type(RefundType.FULL)
                .status(RefundStatus.PENDING).build();
        foreign.setId(70L);
        when(refundRepository.findById(70L)).thenReturn(Optional.of(foreign));

        assertThatThrownBy(() -> service.resolve(command(70L, RefundResolution.FAILED, null, "No aparece")))
                .isInstanceOf(NoSuchElementException.class);
        assertThat(foreign.getStatus()).isEqualTo(RefundStatus.PENDING);
    }

    private OrderRefund pending(Long id, String amount) {
        OrderRefund refund = OrderRefund.builder()
                .order(order)
                .amount(new BigDecimal(amount))
                .type(RefundType.PARTIAL)
                .status(RefundStatus.PENDING)
                .idempotencyKey("refund:10:0:" + amount)
                .build();
        refund.setId(id);
        when(refundRepository.findById(id)).thenReturn(Optional.of(refund));
        return refund;
    }

    private ResolveRefundCommand command(Long refundId, RefundResolution outcome, String reference, String note) {
        return new ResolveRefundCommand(order.getId(), refundId, outcome, reference, note);
    }
}
