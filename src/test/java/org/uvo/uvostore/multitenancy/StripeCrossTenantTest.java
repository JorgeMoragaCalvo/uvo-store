package org.uvo.uvostore.multitenancy;

import com.stripe.Stripe;
import com.stripe.net.Webhook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.uvo.uvostore.entity.catalog.Product;
import org.uvo.uvostore.entity.order.Order;
import org.uvo.uvostore.entity.order.OrderItem;
import org.uvo.uvostore.entity.order.enums.FulfillmentStatus;
import org.uvo.uvostore.entity.order.enums.OrderStatus;
import org.uvo.uvostore.entity.order.enums.PaymentMethodType;
import org.uvo.uvostore.entity.order.enums.PaymentStatus;
import org.uvo.uvostore.entity.tenant.Store;
import org.uvo.uvostore.repository.OrderRepository;
import org.uvo.uvostore.repository.OrderStatusHistoryRepository;
import org.uvo.uvostore.service.settings.SecretCrypto;
import org.uvo.uvostore.support.IntegrationTestSupport;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * GAP-11. El webhook de Stripe verifica la firma con el secreto de la tienda del host, y ese secreto lo elige el
 * admin de la tienda. Una firma válida prueba, entonces, que el evento es de esa tienda, no que la orden que nombra
 * lo sea: la tienda A puede firmar lo que quiera. Por eso estas pruebas firman con el secreto de A y envían al host
 * de A, y lo que comprueban es que nada de eso alcance una orden de B.
 *
 * <p>Los dos controles no sobran. Sin ellos, un evento mal armado (otro {@code api_version}, por ejemplo: el SDK lo
 * acepta y no lee su objeto) dejaría a B intacta y las pruebas negativas pasarían sin probar nada.
 */
class StripeCrossTenantTest extends IntegrationTestSupport {

    private static final String SECRET_A = "whsec_tienda_a";
    private static final String SECRET_B = "whsec_tienda_b";
    private static final long TOTAL = 1000L;

    @Autowired
    private OrderRepository orderRepository;
    @Autowired
    private OrderStatusHistoryRepository historyRepository;

    private Store storeA;
    private Store storeB;

    @BeforeEach
    void twoStoresWithTheirOwnSecret() {
        storeA = createStore("stripe-a");
        storeB = createStore("stripe-b");
        setSetting(storeA, "stripe_webhook_secret", SecretCrypto.encrypt(SECRET_A));
        setSetting(storeB, "stripe_webhook_secret", SecretCrypto.encrypt(SECRET_B));
    }

    @Test
    @DisplayName("Control: el aviso de un intent con el order_id de la propia tienda confirma el pago")
    void anIntentOfTheOwnStoreConfirmsItsOrder() throws Exception {
        Order own = pendingOrder(storeA);

        send(storeA, SECRET_A, "payment_intent.succeeded", intent("pi_propio", own.getId()));

        assertThat(own.getPaymentStatus()).isEqualTo(PaymentStatus.PAID);
        assertThat(own.getStripePaymentIntentId()).isEqualTo("pi_propio");
    }

    @Test
    @DisplayName("Control: el aviso de una sesión de la propia tienda confirma el pago")
    void aSessionOfTheOwnStoreConfirmsItsOrder() throws Exception {
        Order own = pendingOrder(storeA);
        own.setStripeCheckoutSessionId("cs_propia_" + own.getId());

        send(storeA, SECRET_A, "checkout.session.completed", session(own.getStripeCheckoutSessionId()));

        assertThat(own.getPaymentStatus()).isEqualTo(PaymentStatus.PAID);
    }

    @Test
    @DisplayName("Un intent firmado por A con el order_id de B en la metadata no confirma la orden de B")
    void anIntentCannotConfirmAnOrderOfAnotherStoreThroughItsMetadata() throws Exception {
        Order foreign = pendingOrder(storeB);

        send(storeA, SECRET_A, "payment_intent.succeeded", intent("pi_ajeno", foreign.getId()));

        assertUntouched(foreign);
        assertThat(foreign.getStripePaymentIntentId()).isNull();
    }

    @Test
    @DisplayName("Una sesión de B firmada por A no confirma la orden de B")
    void aSessionCannotConfirmAnOrderOfAnotherStore() throws Exception {
        Order foreign = pendingOrder(storeB);
        foreign.setStripeCheckoutSessionId("cs_ajena_" + foreign.getId());

        send(storeA, SECRET_A, "checkout.session.completed", session(foreign.getStripeCheckoutSessionId()));

        assertUntouched(foreign);
    }

    @Test
    @DisplayName("Una sesión de B expirada, firmada por A, no cancela la orden de B")
    void anExpiredSessionCannotCancelAnOrderOfAnotherStore() throws Exception {
        Order foreign = pendingOrder(storeB);
        foreign.setStripeCheckoutSessionId("cs_ajena_" + foreign.getId());

        send(storeA, SECRET_A, "checkout.session.expired", session(foreign.getStripeCheckoutSessionId()));

        assertUntouched(foreign);
    }

    @Test
    @DisplayName("Un intent de B en la columna, firmado por A, no confirma, no rechaza ni cancela la orden de B")
    void anIntentCannotChangeAnOrderOfAnotherStoreThroughTheColumn() throws Exception {
        Order foreign = pendingOrder(storeB);
        String intentId = "pi_ajeno_" + foreign.getId();
        foreign.setStripePaymentIntentId(intentId);

        for (String type : List.of("payment_intent.payment_failed", "payment_intent.canceled", "payment_intent.succeeded")) {
            send(storeA, SECRET_A, type, intent(intentId, null));
            assertUntouched(foreign);
        }
    }

    private void assertUntouched(Order order) {
        orderRepository.flush();
        assertThat(order.getPaymentStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING);
        assertThat(historyRepository.findByOrderIdOrderByCreatedAtDesc(order.getId())).isEmpty();
    }

    // Responde 200 en todos los casos: con una orden ajena, como con una que no existe, Stripe no tiene nada que
    // reintentar. Lo que distingue los casos es el estado de la orden.
    private void send(Store host, String secret, String type, String object) throws Exception {
        String payload = """
                {"id":"evt_%d","object":"event","api_version":"%s","created":%d,"livemode":false,"type":"%s",
                "data":{"object":%s}}""".formatted(nextSeq(), Stripe.API_VERSION, now(), type, object);
        long timestamp = now();
        String signature = Webhook.Util.computeHmacSha256(secret, timestamp + "." + payload);

        mockMvc.perform(post("/api/v1/stripe/webhook")
                        .header("Host", hostHeader(host))
                        .header("Stripe-Signature", "t=" + timestamp + ",v1=" + signature)
                        .contentType("application/json")
                        .content(payload))
                .andExpect(status().isOk());
    }

    private static long now() {
        return System.currentTimeMillis() / 1000;
    }

    private static String intent(String id, Long orderId) {
        String metadata = orderId == null ? "{}" : "{\"order_id\":\"" + orderId + "\"}";
        return """
                {"id":"%s","object":"payment_intent","amount":%d,"currency":"clp","status":"succeeded","metadata":%s}"""
                .formatted(id, TOTAL, metadata);
    }

    private static String session(String id) {
        return """
                {"id":"%s","object":"checkout.session","amount_total":%d,"currency":"clp","payment_status":"paid",
                "status":"complete","payment_intent":"pi_de_%s"}""".formatted(id, TOTAL, id);
    }

    private Order pendingOrder(Store store) {
        Product product = createProduct(store, createCategory(store, "Stripe"), "Artículo", BigDecimal.valueOf(TOTAL));

        Order order = new Order();
        order.setStore(store);
        order.setOrderNumber("ORD-STRIPE-" + nextSeq());
        order.setCustomerEmail("comprador@test.local");
        order.setCustomerFirstName("Test");
        order.setCustomerLastName("Comprador");
        order.setSubtotal(BigDecimal.valueOf(TOTAL));
        order.setDiscountAmount(BigDecimal.ZERO);
        order.setShippingCost(BigDecimal.ZERO);
        order.setTaxAmount(BigDecimal.ZERO);
        order.setTotal(BigDecimal.valueOf(TOTAL));
        order.setStatus(OrderStatus.PENDING);
        order.setPaymentStatus(PaymentStatus.PENDING);
        order.setFulfillmentStatus(FulfillmentStatus.UNFULFILLED);
        order.setPaymentMethod(PaymentMethodType.STRIPE);

        OrderItem item = new OrderItem();
        item.setOrder(order);
        item.setProduct(product);
        item.setProductName(product.getName());
        item.setProductSku(product.getSku());
        item.setQuantity(1);
        item.setPrice(product.getPrice());
        item.setSubtotal(product.getPrice());
        item.setTaxAmount(BigDecimal.ZERO);
        List<OrderItem> items = new ArrayList<>();
        items.add(item);
        order.setItems(items);
        return orderRepository.save(order);
    }
}
