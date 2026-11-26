package org.uvo.uvostore.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.uvo.uvostore.entity.order.Order;
import org.uvo.uvostore.entity.order.enums.FulfillmentStatus;
import org.uvo.uvostore.entity.order.enums.OrderStatus;
import org.uvo.uvostore.entity.order.enums.PaymentMethodType;
import org.uvo.uvostore.entity.order.enums.PaymentStatus;
import org.uvo.uvostore.entity.outbox.OutboxMessage;
import org.uvo.uvostore.entity.outbox.enums.OutboxStatus;
import org.uvo.uvostore.entity.outbox.enums.OutboxType;
import org.uvo.uvostore.entity.security.User;
import org.uvo.uvostore.entity.tenant.Store;
import org.uvo.uvostore.repository.OrderRepository;
import org.uvo.uvostore.repository.OutboxMessageRepository;
import org.uvo.uvostore.service.notification.EmailService;
import org.uvo.uvostore.service.order.PaymentService;
import org.uvo.uvostore.service.payment.MercadoPagoService;
import org.uvo.uvostore.service.payment.ProviderPaymentView;
import org.uvo.uvostore.service.payment.WebpayService;
import org.uvo.uvostore.service.pos.PosClient;
import org.uvo.uvostore.support.IntegrationTestSupport;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.hasItem;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * IMP-09. Una instalación levantada con {@code APP_RECOVERY_MODE=true}: no corre ningún trabajo, no sale nada al POS ni
 * al correo, lo que tiene efectos responde 503, y la API de plataforma da el informe y retiene el outbox.
 *
 * <p>Las pasarelas van como dobles: el informe les pregunta, y esta suite corre sobre la base local, cuyas órdenes de
 * desarrollo no pueden terminar en una llamada de verdad a Transbank.
 */
@TestPropertySource(properties = "app.recovery-mode=true")
class RecoveryModeTest extends IntegrationTestSupport {

    @Value("${app.platform-api-key}")
    private String platformKey;
    @Autowired
    private ApplicationContext context;
    @Autowired
    private OrderRepository orderRepository;
    @Autowired
    private OutboxMessageRepository outboxRepository;
    @Autowired
    private PosClient posClient;
    @Autowired
    private EmailService emailService;

    @MockitoBean
    private WebpayService webpayService;
    @MockitoBean
    private MercadoPagoService mercadoPagoService;
    @MockitoBean
    private PaymentService paymentService;

    @Test
    @DisplayName("El planificador no se enciende: ningún @Scheduled corre")
    void schedulingIsOff() {
        assertThat(context.getBeanNamesForType(org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor.class).length > 0)
                .isFalse();
    }

    @Test
    @DisplayName("La venta al POS y el correo lanzan en vez de salir")
    void posAndMailAreCut() {
        assertThatThrownBy(() -> posClient.notifyOrder(null, null, null)).isInstanceOf(RecoveryModeException.class);
        assertThatThrownBy(() -> emailService.deliver("comprador@test.local", "Asunto", "Cuerpo"))
                .isInstanceOf(RecoveryModeException.class);
    }

    @Test
    @DisplayName("Checkout, retorno de Webpay, webhooks y escrituras del panel responden 503 RECOVERY_MODE")
    void effectfulRequestsGet503() throws Exception {
        Store store = createStore("recup-503");
        Order order = order(store, PaymentMethodType.WEBPAY, PaymentStatus.PAID);
        String token = loginAdmin(store, createAdmin(store, "recup-503"));

        expect503(post("/api/v1/checkout").header("Host", hostHeader(store))
                .contentType("application/json").content("{}"));
        expect503(get("/api/v1/webpay/return").param("token_ws", "x").header("Host", hostHeader(store)));
        expect503(post("/api/v1/stripe/webhook").contentType("application/json").content("{}"));
        expect503(post("/api/webhooks/pos/stock-updated").contentType("application/json").content("{}"));
        expect503(post("/api/admin/orders/" + order.getId() + "/cancel").header("Host", hostHeader(store))
                .header("Authorization", "Bearer " + token));
    }

    @Test
    @DisplayName("Las lecturas y el login del panel siguen funcionando")
    void readsAndLoginStillWork() throws Exception {
        Store store = createStore("recup-lee");
        Order order = order(store, PaymentMethodType.WEBPAY, PaymentStatus.PAID);
        User admin = createAdmin(store, "recup-lee");
        String token = loginAdmin(store, admin);

        mockMvc.perform(get("/api/admin/orders/" + order.getId()).header("Host", hostHeader(store))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("El informe compara cada orden con la pasarela y la clasifica")
    void reportClassifiesAgainstTheGateway() throws Exception {
        Store store = createStore("recup-informe");
        Order paidBoth = order(store, PaymentMethodType.WEBPAY, PaymentStatus.PAID);
        Order chargedOnlyThere = order(store, PaymentMethodType.WEBPAY, PaymentStatus.PENDING);
        Order paidOnlyHere = order(store, PaymentMethodType.MERCADOPAGO, PaymentStatus.PAID);
        Order otherAmount = order(store, PaymentMethodType.STRIPE, PaymentStatus.PAID);
        when(webpayService.inspect(anyLong())).thenReturn(Optional.empty());
        when(webpayService.inspect(eq(paidBoth.getId()))).thenReturn(charged("1000", "0"));
        when(webpayService.inspect(eq(chargedOnlyThere.getId()))).thenReturn(charged("1000", "0"));
        when(mercadoPagoService.inspect(eq(paidOnlyHere.getId())))
                .thenReturn(Optional.of(new ProviderPaymentView("rejected", false, null, null, "mp-1")));
        when(paymentService.inspect(eq(otherAmount.getId()))).thenReturn(charged("900", "0"));

        String path = "$.stores[?(@.storeId == " + store.getId() + ")].payments[?(@.orderId == %d)].classification";
        mockMvc.perform(platform(get("/api/platform/recovery/report")
                        .param("from", Instant.now().minus(Duration.ofHours(1)).toString())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recoveryMode").value(true))
                .andExpect(jsonPath(path.formatted(paidBoth.getId())).value(hasItem("COINCIDE")))
                .andExpect(jsonPath(path.formatted(chargedOnlyThere.getId())).value(hasItem("PROVEEDOR_PAGADO_LOCAL_NO")))
                .andExpect(jsonPath(path.formatted(paidOnlyHere.getId())).value(hasItem("LOCAL_PAGADO_PROVEEDOR_NO")))
                .andExpect(jsonPath(path.formatted(otherAmount.getId())).value(hasItem("MONTO_DISTINTO")))
                // Sin datos del comprador.
                .andExpect(jsonPath("$..customerEmail").isEmpty());
    }

    @Test
    @DisplayName("Retener deja como fallido, con el motivo, lo que estaba en cola hasta el punto de restauración")
    void holdMarksRestoredRowsFailed() throws Exception {
        Store store = createStore("recup-hold");
        Order order = order(store, PaymentMethodType.WEBPAY, PaymentStatus.PAID);
        Instant restoredAt = Instant.now().minus(Duration.ofMinutes(30));
        OutboxMessage before = outboxRow(order, OutboxStatus.PENDING, restoredAt.minus(Duration.ofMinutes(5)));
        OutboxMessage inFlight = outboxRow(order, OutboxStatus.IN_FLIGHT, restoredAt.minus(Duration.ofMinutes(1)));
        OutboxMessage after = outboxRow(order, OutboxStatus.PENDING, restoredAt.plus(Duration.ofMinutes(5)));
        OutboxMessage sent = outboxRow(order, OutboxStatus.SENT, restoredAt.minus(Duration.ofMinutes(5)));

        mockMvc.perform(platform(post("/api/platform/recovery/outbox/hold"))
                        .contentType("application/json").content("{\"restoredAt\":\"" + restoredAt + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.held").isNumber());

        assertThat(reload(before).getStatus()).isEqualTo(OutboxStatus.FAILED);
        assertThat(reload(before).getLastError()).startsWith("Restaurado desde respaldo");
        assertThat(reload(inFlight).getStatus()).isEqualTo(OutboxStatus.FAILED);
        assertThat(reload(after).getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(reload(sent).getStatus()).isEqualTo(OutboxStatus.SENT);
    }

    @Test
    @DisplayName("Sin la clave de plataforma, el informe no se ve")
    void reportRequiresThePlatformKey() throws Exception {
        mockMvc.perform(get("/api/platform/recovery/report").param("from", Instant.now().toString()))
                .andExpect(status().isUnauthorized());
    }

    // --- fixtures ----------------------------------------------------------------------------------------------------

    private void expect503(MockHttpServletRequestBuilder request) throws Exception {
        mockMvc.perform(request)
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "3600"))
                .andExpect(jsonPath("$.error").value(RecoveryModeException.CODE));
    }

    private MockHttpServletRequestBuilder platform(MockHttpServletRequestBuilder request) {
        return request.header("X-Platform-Key", platformKey);
    }

    private static Optional<ProviderPaymentView> charged(String amount, String refunded) {
        return Optional.of(new ProviderPaymentView("AUTHORIZED", true, new BigDecimal(amount),
                new BigDecimal(refunded), "ref-" + nextSeq()));
    }

    private Order order(Store store, PaymentMethodType method, PaymentStatus paymentStatus) {
        Order order = new Order();
        order.setStore(store);
        order.setOrderNumber("ORD-RECUP-" + nextSeq());
        order.setCustomerEmail("comprador@test.local");
        order.setCustomerFirstName("Test");
        order.setCustomerLastName("Comprador");
        order.setSubtotal(new BigDecimal("1000"));
        order.setDiscountAmount(BigDecimal.ZERO);
        order.setShippingCost(BigDecimal.ZERO);
        order.setTaxAmount(BigDecimal.ZERO);
        order.setTotal(new BigDecimal("1000"));
        order.setStatus(paymentStatus == PaymentStatus.PAID ? OrderStatus.PROCESSING : OrderStatus.PENDING);
        order.setPaymentStatus(paymentStatus);
        order.setPaymentMethod(method);
        order.setFulfillmentStatus(FulfillmentStatus.UNFULFILLED);
        return orderRepository.save(order);
    }

    private OutboxMessage outboxRow(Order order, OutboxStatus status, Instant createdAt) {
        return outboxRepository.save(OutboxMessage.builder()
                .order(order)
                .store(order.getStore())
                .type(OutboxType.ORDER_CONFIRMATION_EMAIL)
                .dedupKey("recup-prueba:" + nextSeq())
                .status(status)
                .attempts(1)
                .nextAttemptAt(createdAt)
                .createdAt(createdAt)
                .updatedAt(createdAt)
                .build());
    }

    private OutboxMessage reload(OutboxMessage message) {
        return outboxRepository.findById(message.getId()).orElseThrow();
    }
}
