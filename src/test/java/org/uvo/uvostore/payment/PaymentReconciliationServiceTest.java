package org.uvo.uvostore.payment;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.uvo.uvostore.entity.order.Order;
import org.uvo.uvostore.entity.order.enums.PaymentMethodType;
import org.uvo.uvostore.entity.order.enums.PaymentStatus;
import org.uvo.uvostore.entity.tenant.Store;
import org.uvo.uvostore.repository.OrderRepository;
import org.uvo.uvostore.security.TenantContext;
import org.uvo.uvostore.service.order.OrderStatusServiceImpl;
import org.uvo.uvostore.service.order.PaymentService;
import org.uvo.uvostore.service.order.PaymentVerificationResult;
import org.uvo.uvostore.service.payment.MercadoPagoService;
import org.uvo.uvostore.service.payment.PaymentReconciliationService;
import org.uvo.uvostore.service.payment.WebpayService;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * G1. Un pago cobrado cuyo webhook nunca llegó dejaba la orden en PENDING para siempre: el cliente
 * había pagado y la tienda no se enteraba. Lo que se comprueba aquí es el despacho —a quién se le
 * pregunta por cada orden, con qué tenant, y qué pasa con las que no tienen a quién preguntar—, que
 * no necesita base de datos. La selección va aparte, en {@code PaymentReconciliationSelectionTest}.
 */
class PaymentReconciliationServiceTest {

    private static final AtomicLong SEQ = new AtomicLong(1);

    private final OrderRepository orderRepository = mock(OrderRepository.class);
    private final PaymentService paymentService = mock(PaymentService.class);
    private final WebpayService webpayService = mock(WebpayService.class);
    private final MercadoPagoService mercadoPagoService = mock(MercadoPagoService.class);
    private final DataSource dataSource = mock(DataSource.class);
    private final ResultSet lockResult = mock(ResultSet.class);

    private ListAppender<ILoggingEvent> logs;
    private Logger serviceLogger;

    private PaymentReconciliationService service;

    @BeforeEach
    void setUp() throws SQLException {
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(lockResult);
        when(lockResult.next()).thenReturn(true);
        when(lockResult.getBoolean(1)).thenReturn(true);

        service = new PaymentReconciliationService(orderRepository, paymentService, webpayService,
                mercadoPagoService, dataSource, 15, 24, 50);

        serviceLogger = (Logger) LoggerFactory.getLogger(PaymentReconciliationService.class);
        logs = new ListAppender<>();
        logs.start();
        serviceLogger.addAppender(logs);
    }

    @AfterEach
    void tearDown() {
        serviceLogger.detachAppender(logs);
        TenantContext.clear();
    }

    @Test
    @DisplayName("Cada pasarela se consulta por su lado, y una orden manual no se consulta a nadie")
    void eachGatewayIsAskedItsOwnWayAndManualOrdersAreSkipped() {
        Order webpay = order(PaymentMethodType.WEBPAY, "tok-1", null, Duration.ofHours(1));
        Order mercadoPago = order(PaymentMethodType.MERCADOPAGO, "mp-1", null, Duration.ofHours(1));
        Order stripe = order(PaymentMethodType.STRIPE, null, "cs_test_1", Duration.ofHours(1));
        // Vieja a propósito: lo que distingue "se salta" de "se consulta y no da nada" es que una
        // transferencia pendiente desde hace días es normal y no debe alertar.
        Order manual = order(PaymentMethodType.MANUAL, "transferencia", null, Duration.ofHours(30));
        givenPending(webpay, mercadoPago, stripe, manual);
        when(paymentService.verifyPayment("cs_test_1"))
                .thenReturn(new PaymentVerificationResult("paid", stripe.getId(), stripe.getOrderNumber(), PaymentStatus.PAID.name()));

        service.reconcilePendingPayments();

        verify(webpayService).reconcile(webpay.getId());
        verify(mercadoPagoService).reconcile(mercadoPago.getId());
        verify(paymentService).verifyPayment("cs_test_1");
        // MANUAL no tiene pasarela detrás: una transferencia la confirma una persona, y seguir
        // pendiente es su estado normal mientras tanto. La query no filtra por método, así que si el
        // descarte desaparece del servicio, esto lo caza.
        verify(webpayService, never()).reconcile(manual.getId());
        verify(mercadoPagoService, never()).reconcile(manual.getId());
        assertThat(staleAlerts()).noneMatch(alert -> alert.contains(manual.getOrderNumber()));
    }

    @Test
    @DisplayName("Conciliar nunca confirma un pago: solo pregunta")
    void reconcilingNeverCommitsAPayment() {
        Order webpay = order(PaymentMethodType.WEBPAY, "tok-1", null, Duration.ofHours(1));
        givenPending(webpay);

        service.reconcilePendingPayments();

        // Confirmar aquí una transacción que el cliente abandonó le cobraría. La conciliación existe
        // para averiguar qué pasó, no para hacer que pase.
        verify(webpayService, never()).commitTransaction(anyString());
        verify(webpayService, never()).createTransaction(any(), anyString());
    }

    @Test
    @DisplayName("Cada orden se consulta con el tenant de su tienda, y el contexto queda limpio")
    void eachOrderIsReconciledUnderItsOwnTenant() {
        Order first = order(PaymentMethodType.WEBPAY, "tok-1", null, Duration.ofHours(1));
        Order second = order(PaymentMethodType.WEBPAY, "tok-2", null, Duration.ofHours(1));
        givenPending(first, second);

        List<Long> seen = new ArrayList<>();
        when(webpayService.reconcile(any())).thenAnswer(invocation -> {
            seen.add(TenantContext.requireStoreId());
            return false;
        });

        service.reconcilePendingPayments();

        // Sin esto, un hilo del pool que se quedara con el tenant de la orden anterior cobraría
        // contra las credenciales de otra tienda.
        assertThat(seen).containsExactly(first.getStore().getId(), second.getStore().getId());
        assertThat(TenantContext.get()).isNull();
    }

    @Test
    @DisplayName("Una orden que revienta no se lleva por delante a las demás de la tanda")
    void oneFailingOrderDoesNotStopTheBatch() {
        Order broken = order(PaymentMethodType.WEBPAY, "tok-1", null, Duration.ofHours(1));
        Order healthy = order(PaymentMethodType.WEBPAY, "tok-2", null, Duration.ofHours(1));
        givenPending(broken, healthy);
        when(webpayService.reconcile(broken.getId())).thenThrow(new IllegalStateException("Transbank caído"));

        service.reconcilePendingPayments();

        verify(webpayService).reconcile(healthy.getId());
    }

    @Test
    @DisplayName("Una orden pendiente desde hace más de 24 h se alerta una sola vez por corrida")
    void aStaleOrderIsAlertedOncePerRun() {
        Order stale = order(PaymentMethodType.WEBPAY, "tok-viejo", null, Duration.ofHours(30));
        Order recentEnough = order(PaymentMethodType.WEBPAY, "tok-nuevo", null, Duration.ofHours(2));
        givenPending(stale, recentEnough);

        service.reconcilePendingPayments();

        assertThat(staleAlerts()).hasSize(1);
        assertThat(staleAlerts().get(0)).contains(stale.getOrderNumber());
    }

    @Test
    @DisplayName("La orden que la pasarela resuelve no se alerta, por vieja que sea")
    void anOrderTheGatewayResolvesIsNotAlerted() {
        Order stale = order(PaymentMethodType.WEBPAY, "tok-viejo", null, Duration.ofHours(30));
        givenPending(stale);
        when(webpayService.reconcile(stale.getId())).thenReturn(true);

        service.reconcilePendingPayments();

        assertThat(staleAlerts()).isEmpty();
    }

    @Test
    @DisplayName("Si otra instancia tiene el cerrojo, la corrida no hace nada")
    void aRunWithoutTheLockDoesNothing() throws SQLException {
        when(lockResult.getBoolean(1)).thenReturn(false);

        service.reconcilePendingPayments();

        // Dos instancias preguntando a la vez por la misma orden es justo lo que el cerrojo evita.
        verifyNoInteractions(orderRepository);
        verifyNoInteractions(webpayService);
    }

    private List<String> staleAlerts() {
        return logs.list.stream()
                .filter(event -> event.getLevel() == Level.ERROR)
                .map(ILoggingEvent::getFormattedMessage)
                .filter(message -> message.contains("sin resolver"))
                .toList();
    }

    private void givenPending(Order... orders) {
        when(orderRepository.findPendingPaymentsToReconcile(
                any(), eq(OrderStatusServiceImpl.AMOUNT_MISMATCH_PREFIX + "%"), any()))
                .thenReturn(List.of(orders));
    }

    private Order order(PaymentMethodType method, String paymentId, String stripeSessionId, Duration age) {
        long seq = SEQ.getAndIncrement();
        Store store = Store.builder().name("Tienda " + seq).slug("tienda-" + seq).build();
        store.setId(seq);

        Order order = new Order();
        order.setId(seq);
        order.setStore(store);
        order.setOrderNumber("ORD-" + seq);
        order.setTotal(BigDecimal.valueOf(1000));
        order.setPaymentStatus(PaymentStatus.PENDING);
        order.setPaymentMethod(method);
        order.setPaymentId(paymentId);
        order.setStripeCheckoutSessionId(stripeSessionId);
        order.setCreatedAt(Instant.now().minus(age));
        return order;
    }
}
