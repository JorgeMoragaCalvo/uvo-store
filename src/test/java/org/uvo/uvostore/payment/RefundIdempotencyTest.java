package org.uvo.uvostore.payment;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.uvo.uvostore.entity.order.Order;
import org.uvo.uvostore.entity.order.OrderRefund;
import org.uvo.uvostore.entity.order.enums.OrderStatus;
import org.uvo.uvostore.entity.order.enums.PaymentMethodType;
import org.uvo.uvostore.entity.order.enums.PaymentStatus;
import org.uvo.uvostore.entity.order.enums.RefundStatus;
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
import org.uvo.uvostore.service.payment.RefundIntentStore;
import org.uvo.uvostore.service.payment.RefundService;
import org.uvo.uvostore.service.payment.WebpayService;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * F12. Un reembolso no puede devolver el dinero dos veces.
 *
 * <p>El fallo no era que la pasarela falle: eso lanza, la transacción se deshace y no queda registro de
 * un reembolso que no ocurrió, que es lo correcto. Era el contrario — la pasarela <b>devuelve el
 * dinero</b> y después se cae lo local. El {@code OrderRefund} se escribía después de la llamada, así
 * que tras esa caída no quedaba ni una fila: {@code totalRefunded} seguía diciendo cero, el saldo se
 * ofrecía íntegro y el reintento del operador devolvía el dinero por segunda vez.
 *
 * <p>Ahora la intención se escribe y se confirma antes, así que esa caída deja evidencia y el saldo
 * cuenta ese importe. Aquí se simula ese estado con las intenciones ya guardadas en el repositorio
 * falso, porque lo que hay que fijar es la <b>decisión</b> —si el segundo intento se autoriza o no—, no
 * el mecanismo transaccional, que es de Spring.
 */
class RefundIdempotencyTest {

    private static final BigDecimal TOTAL = new BigDecimal("10000.00");

    private final OrderRepository orderRepository = mock(OrderRepository.class);
    private final OrderRefundRepository refundRepository = mock(OrderRefundRepository.class);
    private final OrderStatusHistoryRepository historyRepository = mock(OrderStatusHistoryRepository.class);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final OrderStatusService orderStatusService = mock(OrderStatusService.class);
    private final PaymentService paymentService = mock(PaymentService.class);
    private final WebpayService webpayService = mock(WebpayService.class);
    private final MercadoPagoService mercadoPagoService = mock(MercadoPagoService.class);
    private final RefundIntentStore intentStore = mock(RefundIntentStore.class);

    private final RefundService service = new RefundService(orderRepository, refundRepository, historyRepository,
            userRepository, orderStatusService, paymentService, webpayService, mercadoPagoService, intentStore);

    /** Lo que hay guardado, para que el saldo se calcule como lo haría la consulta real. */
    private final List<OrderRefund> stored = new ArrayList<>();

    @BeforeEach
    void setUp() {
        Store store = Store.builder().name("Tienda").slug("tienda").build();
        store.setId(1L);
        TenantContext.set(store);

        when(refundRepository.save(any(OrderRefund.class))).thenAnswer(i -> i.getArgument(0));
        // Espejo de la consulta real: suma PENDING y COMPLETED, nunca FAILED. Que este stub y la JPQL
        // digan lo mismo lo garantiza RefundBalanceTest, que sí va contra la base.
        when(refundRepository.totalRefunded(any())).thenAnswer(i -> stored.stream()
                .filter(r -> r.getStatus() != RefundStatus.FAILED)
                .map(OrderRefund::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add));
        when(intentStore.open(any(), any(), any(), any())).thenAnswer(i -> {
            OrderRefund intent = OrderRefund.builder()
                    .order(i.getArgument(0))
                    .amount(i.getArgument(1))
                    .type(i.getArgument(2))
                    .status(RefundStatus.PENDING)
                    .idempotencyKey("refund:1:" + refundRepository.totalRefunded(1L).setScale(0)
                            + ":" + ((BigDecimal) i.getArgument(1)).setScale(0))
                    .build();
            intent.setId(99L);
            stored.add(intent);
            return intent;
        });
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("Tras una caída con el dinero ya devuelto, el reintento se rechaza")
    void aRetryAfterAnUncertainRefundIsRefused() {
        // El escenario del hallazgo: la pasarela devolvió los 10.000 y el registro posterior se cayó, así
        // que quedó una intención PENDING. Antes de F12 no quedaba nada y el saldo se ofrecía íntegro.
        Order order = paidOrder();
        stored.add(OrderRefund.builder().order(order).amount(TOTAL).status(RefundStatus.PENDING).build());

        assertThatThrownBy(() -> service.refund(new RefundCommand(order.getId(), TOTAL, null, null)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("quedan");

        verify(webpayService, org.mockito.Mockito.never()).refund(any(), any());
    }

    @Test
    @DisplayName("La intención se guarda ANTES de llamar a la pasarela")
    void theIntentIsStoredBeforeTheGatewayCall() {
        // Si el orden se invierte, volvemos al fallo: la llamada mueve dinero sin que exista constancia.
        Order order = paidOrder();

        service.refund(new RefundCommand(order.getId(), TOTAL, null, null));

        // Un solo InOrder para los dos mocks: con uno por llamada no se comprobaría ningún orden.
        org.mockito.InOrder ordered = inOrder(intentStore, webpayService);
        ordered.verify(intentStore).open(any(), any(), any(), any());
        ordered.verify(webpayService).refund(order.getId(), new BigDecimal("10000"));
    }

    @Test
    @DisplayName("Un rechazo de la pasarela libera el saldo en vez de morderlo para siempre")
    void aRejectedRefundReleasesTheBalance() {
        Order order = paidOrder();
        when(webpayService.refund(any(), any())).thenThrow(new BusinessException("Transbank rechazó el reembolso"));

        assertThatThrownBy(() -> service.refund(new RefundCommand(order.getId(), TOTAL, null, null)))
                .isInstanceOf(BusinessException.class);

        // La intención se marca fallida; sin eso, el saldo quedaría mordido por un dinero que no salió.
        verify(intentStore).fail(99L);
    }

    @Test
    @DisplayName("La clave de idempotencia viaja a la pasarela que la admite")
    void theKeyIsHandedToTheGatewayThatSupportsIt() {
        // Stripe y MercadoPago la aceptan (verificado en sus SDK); el de Transbank no la expone, y por eso
        // ahí la única protección es la fila de intención.
        Order order = paidOrder();
        order.setPaymentMethod(PaymentMethodType.STRIPE);

        service.refund(new RefundCommand(order.getId(), TOTAL, null, null));

        verify(paymentService).refund(order.getId(), new BigDecimal("10000"), "refund:1:0:10000");
    }

    @Test
    @DisplayName("Un segundo parcial legítimo usa una clave distinta")
    void asecondPartialGetsItsOwnKey() {
        // Si la clave fuera la misma, la pasarela deduplicaría un reembolso que sí es nuevo y el cliente
        // se quedaría sin la segunda mitad.
        Order order = paidOrder();
        order.setPaymentMethod(PaymentMethodType.STRIPE);
        BigDecimal half = new BigDecimal("5000");

        service.refund(new RefundCommand(order.getId(), half, null, null));
        service.refund(new RefundCommand(order.getId(), half, null, null));

        verify(paymentService).refund(order.getId(), new BigDecimal("5000"), "refund:1:0:5000");
        verify(paymentService).refund(order.getId(), new BigDecimal("5000"), "refund:1:5000:5000");
    }

    private Order paidOrder() {
        Order order = Order.builder()
                .id(1L)
                .orderNumber("ORD-REF-1")
                .total(TOTAL)
                .status(OrderStatus.PROCESSING)
                .paymentStatus(PaymentStatus.PAID)
                .paymentMethod(PaymentMethodType.WEBPAY)
                .build();
        order.setStore(TenantContext.requireCurrent());
        when(orderRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(order));
        when(orderRepository.findById(1L)).thenReturn(Optional.of(order));
        return order;
    }
}
