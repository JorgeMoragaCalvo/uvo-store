package org.uvo.uvostore.audit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;
import org.uvo.uvostore.entity.order.Order;
import org.uvo.uvostore.entity.order.enums.FulfillmentStatus;
import org.uvo.uvostore.entity.order.enums.OrderStatus;
import org.uvo.uvostore.entity.order.enums.PaymentMethodType;
import org.uvo.uvostore.entity.order.enums.PaymentStatus;
import org.uvo.uvostore.entity.security.User;
import org.uvo.uvostore.entity.tenant.Store;
import org.uvo.uvostore.repository.OrderRepository;
import org.uvo.uvostore.support.AuditRows;
import org.uvo.uvostore.support.IntegrationTestSupport;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * PROD-18. Lo que mueve dinero o lo que da acceso a moverlo: credenciales de pasarela y de transportista, el reembolso
 * registrado a mano, la cancelación y el estado de pago cambiados desde el panel.
 */
class MoneyConfigAuditTest extends IntegrationTestSupport {

    @Autowired
    private OrderRepository orderRepository;

    private Store store;
    private User admin;
    private String token;

    @BeforeEach
    void setUp() throws Exception {
        store = createStore("audit-money");
        admin = createAdmin(store, "audit-money");
        token = loginAdmin(store, admin);
    }

    @Test
    @DisplayName("Credenciales de pasarela: solo el nombre de la clave y si se puso o se borró; guardar lo mismo no deja fila")
    void gatewayCredentialsAreRecordedByNameOnly() throws Exception {
        String body = "{\"enabled\":true,\"credentials\":{\"childCommerceCode\":\"597099990001\"}}";
        mockMvc.perform(auth(put("/api/admin/payment-gateways/WEBPAY")).contentType("application/json").content(body))
                .andExpect(status().isOk());
        mockMvc.perform(auth(put("/api/admin/payment-gateways/WEBPAY")).contentType("application/json").content(body))
                .andExpect(status().isOk());
        mockMvc.perform(auth(put("/api/admin/payment-gateways/WEBPAY")).contentType("application/json")
                        .content("{\"enabled\":false,\"credentials\":{\"childCommerceCode\":\"\"}}"))
                .andExpect(status().isOk());

        List<AuditRows.Row> rows = auditRows(store, "PAYMENT_GATEWAY_UPDATE");
        assertThat(rows).hasSize(2);
        AuditRows.Row created = rows.get(0);
        assertThat(created.targetType()).isEqualTo("PAYMENT_GATEWAY_CONFIG");
        assertThat(created.json().path("gateway").asText()).isEqualTo("WEBPAY");
        assertThat(created.json().path("created").asBoolean()).isTrue();
        assertThat(created.change("credentials").path("childCommerceCode").asText()).isEqualTo("set");
        assertThat(created.change("enabled").path("to").asBoolean()).isTrue();
        AuditRows.Row cleared = rows.get(1);
        assertThat(cleared.change("credentials").path("childCommerceCode").asText()).isEqualTo("cleared");
        assertThat(cleared.change("enabled").path("to").asBoolean()).isFalse();
        assertThat(rows).allSatisfy(row -> assertThat(row.details()).doesNotContain("597099990001"));
    }

    @Test
    @DisplayName("Credenciales de transportista: el nombre de la clave, nunca el valor")
    void carrierCredentialsAreRecordedByNameOnly() throws Exception {
        String created = mockMvc.perform(auth(post("/api/admin/shipping/methods")).contentType("application/json")
                        .content("{\"name\":\"Courier\",\"code\":\"courier-" + nextSeq()
                                + "\",\"type\":\"COURIER\",\"hasApiIntegration\":false,\"active\":true,\"sortOrder\":0}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        long methodId = objectMapper.readTree(created).get("id").asLong();

        for (int i = 0; i < 2; i++) {
            mockMvc.perform(auth(put("/api/admin/shipping/methods/" + methodId + "/credentials"))
                            .contentType("application/json")
                            .content("{\"subscriptionKey\":\"clave-chilexpress-secreta\"}"))
                    .andExpect(status().isOk());
        }

        AuditRows.Row row = auditRow(store, "SHIPPING_METHOD_CREDENTIALS_UPDATE");
        assertThat(row.targetId()).isEqualTo(methodId);
        assertThat(row.change("credentials").path("subscriptionKey").asText()).isEqualTo("set");
        assertThat(row.details()).doesNotContain("clave-chilexpress-secreta");
    }

    @Test
    @DisplayName("Un reembolso registrado a mano deja el monto, sin el motivo")
    void anExternalRefundIsRecordedWithoutTheReason() throws Exception {
        Order order = order(OrderStatus.PROCESSING, PaymentStatus.PAID);

        mockMvc.perform(auth(post("/api/admin/orders/" + order.getId() + "/refund/external"))
                        .contentType("application/json")
                        .content("{\"amount\":1000,\"reason\":\"devuelto por transferencia al cliente\"}"))
                .andExpect(status().isOk());

        AuditRows.Row row = auditRow(store, "REFUND_RECORD_EXTERNAL");
        assertThat(row.targetType()).isEqualTo("ORDER");
        assertThat(row.targetId()).isEqualTo(order.getId());
        assertThat(row.json().path("amount").asText()).isEqualTo("1000");
        assertThat(row.json().path("closesOrder").asBoolean()).isFalse();
        assertThat(row.details()).doesNotContain("transferencia");
    }

    @Test
    @DisplayName("Cancelar por cualquiera de las dos vías deja fila; cancelar una ya cancelada no")
    void cancellingIsRecordedOncePerOrder() throws Exception {
        Order byButton = order(OrderStatus.PENDING, PaymentStatus.PENDING);
        Order byStatus = order(OrderStatus.PROCESSING, PaymentStatus.PENDING);

        mockMvc.perform(auth(post("/api/admin/orders/" + byButton.getId() + "/cancel"))).andExpect(status().isOk());
        mockMvc.perform(auth(post("/api/admin/orders/" + byButton.getId() + "/cancel"))).andExpect(status().isOk());
        mockMvc.perform(auth(put("/api/admin/orders/" + byStatus.getId() + "/status"))
                        .contentType("application/json").content("{\"status\":\"CANCELLED\"}"))
                .andExpect(status().isOk());

        List<AuditRows.Row> rows = auditRows(store, "ORDER_CANCEL");
        assertThat(rows).extracting(row -> row.targetId() + ":" + row.json().path("via").asText()
                        + ":" + row.json().path("fromStatus").asText())
                .containsExactly(byButton.getId() + ":cancel:PENDING", byStatus.getId() + ":status:PROCESSING");
    }

    @Test
    @DisplayName("Marcar pagada o fallida a mano deja el antes y el después; repetirlo no deja otra fila")
    void manualPaymentStatusChangesAreRecorded() throws Exception {
        Order paid = order(OrderStatus.PENDING, PaymentStatus.PENDING);
        Order failed = order(OrderStatus.PENDING, PaymentStatus.PENDING);

        mockMvc.perform(paymentStatus(paid, "PAID")).andExpect(status().isOk());
        // Lo que responda la segunda vez da igual aquí: lo que importa es que no deja otra fila.
        mockMvc.perform(paymentStatus(paid, "PAID"));
        mockMvc.perform(paymentStatus(failed, "FAILED")).andExpect(status().isOk());

        List<AuditRows.Row> rows = auditRows(store, "ORDER_PAYMENT_STATUS_UPDATE");
        assertThat(rows).extracting(row -> row.targetId() + ":" + row.change("paymentStatus").path("from").asText()
                        + "->" + row.change("paymentStatus").path("to").asText())
                .containsExactly(paid.getId() + ":PENDING->PAID", failed.getId() + ":PENDING->FAILED");
        assertThat(rows.getFirst().actorId()).isEqualTo(admin.getId());
    }

    private AbstractMockHttpServletRequestBuilder<?> paymentStatus(Order order, String status) {
        return auth(put("/api/admin/orders/" + order.getId() + "/payment-status"))
                .contentType("application/json")
                .content("{\"status\":\"" + status + "\"}");
    }

    private Order order(OrderStatus status, PaymentStatus paymentStatus) {
        Order order = new Order();
        order.setStore(store);
        order.setOrderNumber("ORD-AUD-" + nextSeq());
        order.setCustomerEmail("comprador@test.local");
        order.setCustomerFirstName("Test");
        order.setCustomerLastName("Comprador");
        order.setSubtotal(BigDecimal.valueOf(10000));
        order.setDiscountAmount(BigDecimal.ZERO);
        order.setShippingCost(BigDecimal.ZERO);
        order.setTaxAmount(BigDecimal.ZERO);
        order.setTotal(BigDecimal.valueOf(10000));
        order.setStatus(status);
        order.setPaymentStatus(paymentStatus);
        order.setPaymentMethod(PaymentMethodType.MANUAL);
        order.setFulfillmentStatus(FulfillmentStatus.UNFULFILLED);
        return orderRepository.save(order);
    }

    private <T extends AbstractMockHttpServletRequestBuilder<?>> T auth(T request) {
        request.header("Host", hostHeader(store)).header("Authorization", "Bearer " + token);
        return request;
    }
}
