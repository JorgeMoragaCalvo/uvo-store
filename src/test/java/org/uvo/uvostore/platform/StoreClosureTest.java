package org.uvo.uvostore.platform;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.uvo.uvostore.entity.catalog.Product;
import org.uvo.uvostore.entity.customer.Customer;
import org.uvo.uvostore.entity.order.Order;
import org.uvo.uvostore.entity.order.OrderRefund;
import org.uvo.uvostore.entity.order.enums.FulfillmentStatus;
import org.uvo.uvostore.entity.order.enums.OrderStatus;
import org.uvo.uvostore.entity.order.enums.PaymentStatus;
import org.uvo.uvostore.entity.order.enums.RefundStatus;
import org.uvo.uvostore.entity.order.enums.RefundType;
import org.uvo.uvostore.entity.outbox.OutboxMessage;
import org.uvo.uvostore.entity.outbox.enums.OutboxStatus;
import org.uvo.uvostore.entity.outbox.enums.OutboxType;
import org.uvo.uvostore.entity.payment.PaymentGatewayConfig;
import org.uvo.uvostore.entity.payment.enums.PaymentGatewayType;
import org.uvo.uvostore.entity.pos.PosConnection;
import org.uvo.uvostore.entity.pos.ProductSyncMapping;
import org.uvo.uvostore.entity.pos.enums.SyncDirection;
import org.uvo.uvostore.entity.pos.enums.SyncStatus;
import org.uvo.uvostore.entity.security.User;
import org.uvo.uvostore.entity.shipping.ShippingMethod;
import org.uvo.uvostore.entity.shipping.enums.ShippingCarrier;
import org.uvo.uvostore.entity.tenant.Store;
import org.uvo.uvostore.entity.tenant.enums.StoreStatus;
import org.uvo.uvostore.repository.OrderRefundRepository;
import org.uvo.uvostore.repository.OrderRepository;
import org.uvo.uvostore.repository.OutboxMessageRepository;
import org.uvo.uvostore.repository.PaymentGatewayConfigRepository;
import org.uvo.uvostore.repository.PosConnectionRepository;
import org.uvo.uvostore.repository.ProductSyncMappingRepository;
import org.uvo.uvostore.repository.ShippingMethodRepository;
import org.uvo.uvostore.support.AuditRows;
import org.uvo.uvostore.support.IntegrationTestSupport;
import org.uvo.uvostore.support.WithPosIntegration;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * PROD-23. Cerrar una tienda: le quita todo lo que permite entrar o actuar en su nombre y conserva sus datos. Solo
 * desde SUSPENDED, con el nick escrito y sin nada en vuelo.
 *
 * <p>{@code @WithPosIntegration} por el caso de la empresa que vuelve a darse de alta: el bloque {@code pos} del alta
 * se rechaza con la integración apagada.
 */
@WithPosIntegration
class StoreClosureTest extends IntegrationTestSupport {

    private static final String GATEWAY_SECRET = "gw-secreto-cierre-123";
    private static final String CARRIER_SECRET = "chx-secreto-cierre-456";
    private static final String STRIPE_SECRET = "stripe-secreto-cierre-789";
    private static final String POS_SECRET = "pos-secreto-cierre-000";

    @Value("${app.platform-api-key}")
    private String platformKey;

    @Autowired
    private PaymentGatewayConfigRepository gatewayRepository;
    @Autowired
    private ShippingMethodRepository shippingMethodRepository;
    @Autowired
    private PosConnectionRepository connectionRepository;
    @Autowired
    private ProductSyncMappingRepository mappingRepository;
    @Autowired
    private OrderRepository orderRepository;
    @Autowired
    private OutboxMessageRepository outboxRepository;
    @Autowired
    private OrderRefundRepository refundRepository;
    @PersistenceContext
    private EntityManager entityManager;

    @Test
    @DisplayName("Cerrar revoca accesos, borra credenciales y la conexión con el POS, libera el dominio y conserva los datos")
    void closingRevokesAndRemovesEverythingButTheData() throws Exception {
        Full full = fullStore();
        String ownerToken = loginAdmin(full.store, full.owner);
        suspend(full.store);
        int ownerVersion = full.owner.getTokenVersion();

        mockMvc.perform(platform(post("/api/platform/stores/" + full.store.getId() + "/close"))
                        .header("X-Platform-Operator", "op-cierre")
                        .content(closeBody(full.store.getSlug())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"))
                .andExpect(jsonPath("$.domain").isEmpty())
                .andExpect(jsonPath("$.posConnections").isEmpty());

        entityManager.flush();
        entityManager.clear();
        Store closed = storeRepository.findById(full.store.getId()).orElseThrow();
        assertThat(closed.getStatus()).isEqualTo(StoreStatus.CLOSED);
        assertThat(closed.getDomain()).isNull();
        assertThat(closed.getDomainVerifiedAt()).isNull();
        assertThat(closed.getStatusChangedAt()).isNotNull();
        assertThat(closed.getSlug()).isEqualTo(full.store.getSlug());

        // Administradores: ni contraseña, ni tokens, ni sesiones, ni cuenta activa.
        for (User admin : userRepository.findAllByStoreIdOrderById(full.store.getId())) {
            assertThat(admin.isActive()).isFalse();
            assertThat(admin.getPassword()).isNull();
            assertThat(admin.getInvitationToken()).isNull();
            assertThat(admin.getPasswordResetToken()).isNull();
        }
        assertThat(userRepository.findById(full.owner.getId()).orElseThrow().getTokenVersion()).isEqualTo(ownerVersion + 1);
        // Clientes, igual, en una sola sentencia.
        Customer customer = customerRepository.findById(full.customer.getId()).orElseThrow();
        assertThat(customer.getPassword()).isNull();
        assertThat(customer.getTokenVersion()).isEqualTo(full.customer.getTokenVersion() + 1);

        // Credenciales.
        assertThat(count("SELECT count(*) FROM payment_gateway_configs WHERE store_id = ?", full.store.getId())).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT api_credentials FROM shipping_methods WHERE id = ?", String.class,
                full.carrier.getId())).isNull();
        assertThat(settingRepository.findByStoreIdAndSettingKey(full.store.getId(), "stripe_secret_key")).isEmpty();
        assertThat(settingRepository.findByStoreIdAndSettingKey(full.store.getId(), "stripe_enabled"))
                .hasValueSatisfying(s -> assertThat(s.getValue()).isEqualTo("false"));
        assertThat(settingRepository.findByStoreIdAndSettingKey(full.store.getId(), "pos_sync_enabled"))
                .hasValueSatisfying(s -> assertThat(s.getValue()).isEqualTo("false"));

        // POS: ni conexión ni mapeos; el producto sigue.
        assertThat(count("SELECT count(*) FROM pos_connections WHERE store_id = ?", full.store.getId())).isZero();
        assertThat(count("SELECT count(*) FROM product_sync_mapping WHERE product_id = ?", full.product.getId())).isZero();

        // Los datos se conservan.
        assertThat(productRepository.findById(full.product.getId())).isPresent();
        assertThat(orderRepository.findById(full.manualOrder.getId())).isPresent();
        assertThat(customerRepository.findById(full.customer.getId())).isPresent();

        // El Host dice que está cerrada, también con la sesión que tenía el dueño.
        mockMvc.perform(get("/api/admin/products").header("Host", hostHeader(full.store))
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("Esta tienda está cerrada"));

        AuditRows.Row row = auditRow(full.store, "STORE_CLOSE");
        assertThat(row.actorType()).isEqualTo("PLATFORM");
        assertThat(row.actorLabel()).isEqualTo("op-cierre");
        JsonNode details = row.json();
        assertThat(details.path("reason").asText()).isEqualTo("MERCHANT_REQUEST");
        assertThat(details.path("previousDomain").asText()).isEqualTo(full.domain);
        assertThat(details.path("usersRevoked").asInt()).isEqualTo(2);
        assertThat(details.path("customersRevoked").asInt()).isEqualTo(1);
        assertThat(details.path("gatewaysRemoved").toString()).isEqualTo("[\"WEBPAY\"]");
        assertThat(details.path("shippingCredentialsRemoved").toString()).contains(full.carrier.getCode());
        assertThat(details.path("stripeSecretsRemoved").asInt()).isEqualTo(1);
        assertThat(details.path("posCompanyIds").toString()).isEqualTo("[" + full.connection.getCompanyId() + "]");
        assertThat(details.path("syncMappingsRemoved").asInt()).isEqualTo(1);
        assertThat(row.change("status").path("to").asText()).isEqualTo("CLOSED");
        assertThat(row.details()).doesNotContain(GATEWAY_SECRET, CARRIER_SECRET, STRIPE_SECRET, POS_SECRET,
                full.customer.getEmail());
    }

    @Test
    @DisplayName("Una tienda activa no se cierra: primero se suspende")
    void anActiveStoreCannotBeClosed() throws Exception {
        Store store = createStore("close-active");

        mockMvc.perform(platform(post("/api/platform/stores/" + store.getId() + "/close"))
                        .content(closeBody(store.getSlug())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("suspendida")));

        assertThat(storeRepository.findById(store.getId()).orElseThrow().getStatus()).isEqualTo(StoreStatus.ACTIVE);
    }

    @Test
    @DisplayName("Con el nick equivocado no se cierra nada")
    void theWrongSlugClosesNothing() throws Exception {
        Store store = createStore("close-slug");
        suspend(store);

        mockMvc.perform(platform(post("/api/platform/stores/" + store.getId() + "/close"))
                        .content(closeBody(store.getSlug() + "-otra")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("nick")));
        mockMvc.perform(platform(post("/api/platform/stores/" + store.getId() + "/close"))
                        .content("{\"confirmSlug\":\"" + store.getSlug() + "\"}"))
                .andExpect(status().isBadRequest());

        assertThat(storeRepository.findById(store.getId()).orElseThrow().getStatus()).isEqualTo(StoreStatus.SUSPENDED);
        assertThat(auditRows(store, "STORE_CLOSE")).isEmpty();
    }

    @Test
    @DisplayName("Un pago de pasarela sin resolver impide cerrar")
    void aPendingGatewayPaymentBlocksTheClosing() throws Exception {
        Store store = createStore("close-pay");
        Order order = order(store, PaymentStatus.PENDING, true);
        order.setPaymentId("tok-pendiente");
        orderRepository.save(order);
        // Una orden manual pendiente, en cambio, no bloquea: no hay dinero en vuelo.
        order(store, PaymentStatus.PENDING, true);
        suspend(store);

        assertRefused(store, "1 pagos de pasarela sin resolver");
    }

    @Test
    @DisplayName("Una orden pagada sin el stock descontado impide cerrar")
    void aPaidOrderWithoutStockBlocksTheClosing() throws Exception {
        Store store = createStore("close-stock");
        order(store, PaymentStatus.PAID, false);
        suspend(store);

        assertRefused(store, "1 órdenes pagadas sin el stock descontado");
    }

    @Test
    @DisplayName("Un envío pendiente del outbox impide cerrar")
    void aPendingOutboxMessageBlocksTheClosing() throws Exception {
        Store store = createStore("close-outbox");
        Order order = order(store, PaymentStatus.PAID, true);
        OutboxMessage message = new OutboxMessage();
        message.setOrder(order);
        message.setType(OutboxType.POS_ORDER);
        message.setCompanyId(nextSeq());
        message.setDedupKey("close-test:" + nextSeq());
        message.setStatus(OutboxStatus.PENDING);
        message.setNextAttemptAt(Instant.now());
        message.setCreatedAt(Instant.now());
        outboxRepository.save(message);
        suspend(store);

        assertRefused(store, "1 envíos pendientes");
    }

    @Test
    @DisplayName("Un reembolso de resultado desconocido impide cerrar")
    void aPendingRefundBlocksTheClosing() throws Exception {
        Store store = createStore("close-refund");
        Order order = order(store, PaymentStatus.PAID, true);
        OrderRefund refund = new OrderRefund();
        refund.setOrder(order);
        refund.setAmount(BigDecimal.valueOf(1000));
        refund.setType(RefundType.PARTIAL);
        refund.setStatus(RefundStatus.PENDING);
        refundRepository.save(refund);
        suspend(store);

        assertRefused(store, "1 reembolsos de resultado desconocido");
    }

    @Test
    @DisplayName("La empresa del POS de una tienda cerrada puede darse de alta otra vez, con otro nick; el viejo sigue ocupado")
    void theCompanyIsFreedButTheSlugIsNot() throws Exception {
        Store store = createStore("close-pos");
        long companyId = 880_000_000L + nextSeq();
        connection(store, companyId);
        suspend(store);
        mockMvc.perform(platform(post("/api/platform/stores/" + store.getId() + "/close"))
                        .content(closeBody(store.getSlug())))
                .andExpect(status().isOk());

        mockMvc.perform(platform(post("/api/platform/stores")).content(posOnboarding("close-pos-nueva-" + nextSeq(), companyId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(true));
        mockMvc.perform(platform(post("/api/platform/stores")).content("""
                        {"slug":"%s","domain":"","storeName":"Otra","adminName":"Otro","adminEmail":"otro-%d@test.local"}"""
                        .formatted(store.getSlug(), nextSeq())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Ese nick ya está en uso"));
    }

    @Test
    @DisplayName("Cerrar otra vez con el nick correcto responde lo mismo y no deja otra fila")
    void closingTwiceIsANoOp() throws Exception {
        Store store = createStore("close-twice");
        suspend(store);

        for (int i = 0; i < 2; i++) {
            mockMvc.perform(platform(post("/api/platform/stores/" + store.getId() + "/close"))
                            .content(closeBody(store.getSlug())))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("CLOSED"));
        }

        assertThat(auditRows(store, "STORE_CLOSE")).hasSize(1);
    }

    // --- fixtures ----------------------------------------------------------------------------------

    private record Full(Store store, String domain, User owner, Customer customer, ShippingMethod carrier,
                        PosConnection connection, Product product, Order manualOrder) {
    }

    /** Una tienda con algo de todo lo que el cierre toca. */
    private Full fullStore() {
        Store store = createStore("close-full");
        String domain = "cierre-" + nextSeq() + ".example.com";
        store.setDomain(domain);
        store.setDomainVerifiedAt(Instant.now());
        User owner = createAdmin(store, "close-owner");
        store.setOwnerUserId(owner.getId());
        storeRepository.save(store);
        User invited = createAdmin(store, "close-invited");
        invited.setPassword(null);
        invited.setInvitationToken("inv-" + nextSeq() + "-" + "x".repeat(40));
        invited.setInvitationSentAt(Instant.now());
        userRepository.save(invited);

        Customer customer = createCustomer(store, "close-customer");

        PaymentGatewayConfig gateway = new PaymentGatewayConfig();
        gateway.setStore(store);
        gateway.setGateway(PaymentGatewayType.WEBPAY);
        gateway.setEnabled(true);
        gateway.setCredentials(new HashMap<>(Map.of("apiKey", GATEWAY_SECRET)));
        gatewayRepository.save(gateway);

        ShippingMethod carrier = new ShippingMethod();
        carrier.setStore(store);
        carrier.setName("Chilexpress");
        carrier.setCode("chx-" + nextSeq());
        carrier.setHasApiIntegration(true);
        carrier.setCarrier(ShippingCarrier.CHILEXPRESS);
        carrier.setApiCredentials(new HashMap<>(Map.of("subscriptionKey", CARRIER_SECRET)));
        carrier.setActive(true);
        carrier = shippingMethodRepository.save(carrier);

        setSetting(store, "stripe_secret_key", STRIPE_SECRET);
        setSetting(store, "stripe_enabled", "true");
        setSetting(store, "pos_sync_enabled", "true");

        PosConnection connection = connection(store, nextSeq());
        Product product = createProduct(store, createCategory(store, "Cierre"), "Producto cierre", BigDecimal.valueOf(990));
        ProductSyncMapping mapping = new ProductSyncMapping();
        mapping.setProduct(product);
        mapping.setExternalId(nextSeq());
        mapping.setExternalSku(product.getSku());
        mapping.setCompanyId(connection.getCompanyId());
        mapping.setSyncStatus(SyncStatus.ACTIVE);
        mapping.setSyncDirection(SyncDirection.FROM_POS);
        mappingRepository.save(mapping);

        Order manualOrder = order(store, PaymentStatus.PENDING, true);
        return new Full(store, domain, owner, customer, carrier, connection, product, manualOrder);
    }

    private PosConnection connection(Store store, long companyId) {
        PosConnection connection = new PosConnection();
        connection.setStore(store);
        connection.setCompanyName("Empresa cierre");
        connection.setCompanyId(companyId);
        connection.setApiUrl("https://pos.test.local/api");
        connection.setApiKey("uvp_" + companyId + "_" + POS_SECRET);
        connection.setWebhookSecret(POS_SECRET);
        connection.setActive(true);
        return connectionRepository.save(connection);
    }

    private Order order(Store store, PaymentStatus paymentStatus, boolean stockApplied) {
        Order order = new Order();
        order.setStore(store);
        order.setOrderNumber("ORD-CLOSE-" + nextSeq());
        order.setCustomerEmail("comprador@test.local");
        order.setCustomerFirstName("Test");
        order.setCustomerLastName("Comprador");
        order.setSubtotal(BigDecimal.valueOf(1000));
        order.setDiscountAmount(BigDecimal.ZERO);
        order.setShippingCost(BigDecimal.ZERO);
        order.setTaxAmount(BigDecimal.ZERO);
        order.setTotal(BigDecimal.valueOf(1000));
        order.setStatus(paymentStatus == PaymentStatus.PAID ? OrderStatus.PROCESSING : OrderStatus.PENDING);
        order.setPaymentStatus(paymentStatus);
        order.setFulfillmentStatus(FulfillmentStatus.UNFULFILLED);
        order.setStockApplied(stockApplied);
        return orderRepository.save(order);
    }

    private void suspend(Store store) throws Exception {
        mockMvc.perform(platform(post("/api/platform/stores/" + store.getId() + "/suspend"))
                        .content("{\"reason\":\"MERCHANT_REQUEST\"}"))
                .andExpect(status().isOk());
    }

    private void assertRefused(Store store, String expected) throws Exception {
        mockMvc.perform(platform(post("/api/platform/stores/" + store.getId() + "/close"))
                        .content(closeBody(store.getSlug())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString(expected)));
        assertThat(storeRepository.findById(store.getId()).orElseThrow().getStatus()).isEqualTo(StoreStatus.SUSPENDED);
        assertThat(auditRows(store, "STORE_CLOSE")).isEmpty();
    }

    private long count(String sql, Object... args) {
        return jdbcTemplate.queryForObject(sql, Long.class, args);
    }

    private static String closeBody(String slug) {
        return "{\"confirmSlug\":\"" + slug + "\",\"reason\":\"MERCHANT_REQUEST\"}";
    }

    private static String posOnboarding(String slug, long companyId) {
        return """
                {"slug":"%s","domain":"","storeName":"Tienda nueva","adminName":"Dueño","adminEmail":"nuevo-%d@test.local",
                 "pos":{"companyId":%d,"companyName":"Empresa","apiUrl":"http://pos.internal/api",
                        "apiKey":"uvp_%d_%s","webhookSecret":"%s"}}"""
                .formatted(slug, companyId, companyId, companyId, "n".repeat(40), "w".repeat(40));
    }

    private MockHttpServletRequestBuilder platform(MockHttpServletRequestBuilder builder) {
        return builder.header("X-Platform-Key", platformKey).contentType("application/json");
    }
}
