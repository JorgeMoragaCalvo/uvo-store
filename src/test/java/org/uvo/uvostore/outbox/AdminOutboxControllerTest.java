package org.uvo.uvostore.outbox;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
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
import org.uvo.uvostore.support.IntegrationTestSupport;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * GAP-04. La página «Envíos fallidos» del panel: qué lista, quién puede actuar y qué rechaza. Lo que pasa después del
 * reintento (la entrega, la clave, la carrera entre dos operadores) está en {@code OutboxInterruptionTest}, con commit
 * real; aquí todo se deshace.
 */
class AdminOutboxControllerTest extends IntegrationTestSupport {

    @Autowired
    private OrderRepository orderRepository;
    @Autowired
    private OutboxMessageRepository outboxRepository;

    @Test
    @DisplayName("Lista solo las fallidas de la tienda, marca las inciertas y las heredadas, y las cuenta en el Dashboard")
    void listsTheFailedDeliveriesOfTheStore() throws Exception {
        Store store = createStore("outbox-panel");
        String token = loginAdmin(store, createAdmin(store, "outbox-panel"));
        Order order = order(store);
        OutboxMessage uncertain = row(order, OutboxType.POS_ORDER, 77L, OutboxStatus.FAILED,
                "Resultado incierto, la venta pudo haber llegado al POS: Read timed out");
        OutboxMessage rejected = row(order, OutboxType.ORDER_CONFIRMATION_EMAIL, null, OutboxStatus.FAILED,
                "Rechazado: dirección inválida");
        OutboxMessage legacy = row(order, OutboxType.POS_ORDER, null, OutboxStatus.FAILED, "Sin empresa");
        row(order, OutboxType.ORDER_PLACED_EMAIL, null, OutboxStatus.SENT, null);
        Store other = createStore("outbox-other");
        row(order(other), OutboxType.POS_ORDER, 77L, OutboxStatus.FAILED, "Resultado incierto");

        mockMvc.perform(admin(get("/api/admin/outbox/failed"), store, token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.content[*].id").value(containsInAnyOrder(
                        uncertain.getId().intValue(), rejected.getId().intValue(), legacy.getId().intValue())))
                .andExpect(jsonPath("$.content[?(@.id == %d)].uncertain".formatted(uncertain.getId())).value(true))
                .andExpect(jsonPath("$.content[?(@.id == %d)].channel".formatted(uncertain.getId())).value("POS"))
                .andExpect(jsonPath("$.content[?(@.id == %d)].orderNumber".formatted(uncertain.getId()))
                        .value(order.getOrderNumber()))
                .andExpect(jsonPath("$.content[?(@.id == %d)].uncertain".formatted(rejected.getId())).value(false))
                .andExpect(jsonPath("$.content[?(@.id == %d)].legacy".formatted(legacy.getId())).value(true));
        mockMvc.perform(admin(get("/api/admin/orders/stats"), store, token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outboxFailed").value(3));
    }

    @Test
    @DisplayName("Reintentar pide nota, y en una venta incierta la confirmación de que se revisó el POS")
    void retryRequiresANoteAndTheConfirmationForAnUncertainSale() throws Exception {
        Store store = createStore("outbox-retry");
        String token = loginAdmin(store, createAdmin(store, "outbox-retry"));
        Order order = order(store);
        OutboxMessage sale = row(order, OutboxType.POS_ORDER, 77L, OutboxStatus.FAILED,
                "Huérfana en vuelo: el proceso se interrumpió durante la entrega, que pudo haber ocurrido");

        mockMvc.perform(admin(post("/api/admin/outbox/" + sale.getId() + "/retry"), store, token)
                        .contentType("application/json").content("{\"note\":\"  \",\"posChecked\":true}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(admin(post("/api/admin/outbox/" + sale.getId() + "/retry"), store, token)
                        .contentType("application/json").content("{\"note\":\"Reintento\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("revisaste en el POS")));
        assertThat(outboxRepository.findById(sale.getId()).orElseThrow().getStatus()).isEqualTo(OutboxStatus.FAILED);

        mockMvc.perform(admin(post("/api/admin/outbox/" + sale.getId() + "/retry"), store, token)
                        .contentType("application/json").content("{\"note\":\"No está en el POS\",\"posChecked\":true}"))
                .andExpect(status().isNoContent());

        // De la base y no del repositorio: el cambio es un UPDATE condicional, y la entidad que esta transacción de
        // prueba ya tiene en memoria no lo ve.
        var requeued = jdbcTemplate.queryForMap(
                "SELECT status, attempts, requeued_at FROM outbox_messages WHERE id = ?", sale.getId());
        assertThat(requeued.get("status")).isEqualTo("PENDING");
        assertThat(requeued.get("attempts")).as("el despachador compara los intentos al cerrar").isEqualTo(1);
        assertThat(requeued.get("requeued_at")).isNotNull();
        assertThat(auditRow(store, "OUTBOX_RETRY").details()).contains(sale.getId().toString());
        // Ya no está fallida: el segundo clic no la vuelve a encolar.
        mockMvc.perform(admin(post("/api/admin/outbox/" + sale.getId() + "/retry"), store, token)
                        .contentType("application/json").content("{\"note\":\"Otra vez\",\"posChecked\":true}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Una fila de otra tienda no existe para este panel, y sin orders.manage solo se mira")
    void otherStoresRowsAndReadOnlyAdmins() throws Exception {
        Store store = createStore("outbox-scope");
        String token = loginAdmin(store, createAdmin(store, "outbox-scope"));
        Store other = createStore("outbox-scope-other");
        OutboxMessage foreign = row(order(other), OutboxType.ORDER_CONFIRMATION_EMAIL, null, OutboxStatus.FAILED,
                "Rechazado: dirección inválida");

        mockMvc.perform(admin(post("/api/admin/outbox/" + foreign.getId() + "/resolve"), store, token)
                        .contentType("application/json").content("{\"note\":\"No es mía\"}"))
                .andExpect(status().isNotFound());
        assertThat(outboxRepository.findById(foreign.getId()).orElseThrow().getStatus()).isEqualTo(OutboxStatus.FAILED);

        User viewer = createAdminWithPermissions(store, "outbox-viewer", "orders.view");
        String viewerToken = loginAdmin(store, viewer);
        OutboxMessage mine = row(order(store), OutboxType.ORDER_CONFIRMATION_EMAIL, null, OutboxStatus.FAILED,
                "Rechazado: dirección inválida");
        mockMvc.perform(admin(get("/api/admin/outbox/failed"), store, viewerToken))
                .andExpect(status().isOk());
        mockMvc.perform(admin(post("/api/admin/outbox/" + mine.getId() + "/resolve"), store, viewerToken)
                        .contentType("application/json").content("{\"note\":\"Enviado a mano\"}"))
                .andExpect(status().isForbidden());
    }

    // --- fixtures ----------------------------------------------------------------------------------------------------

    private Order order(Store store) {
        Order order = new Order();
        order.setStore(store);
        order.setOrderNumber("ORD-OUTBOX-PANEL-" + nextSeq());
        order.setCustomerEmail("comprador@test.local");
        order.setCustomerFirstName("Test");
        order.setCustomerLastName("Comprador");
        order.setSubtotal(new BigDecimal("1000"));
        order.setDiscountAmount(BigDecimal.ZERO);
        order.setShippingCost(BigDecimal.ZERO);
        order.setTaxAmount(BigDecimal.ZERO);
        order.setTotal(new BigDecimal("1000"));
        order.setStatus(OrderStatus.PROCESSING);
        order.setPaymentStatus(PaymentStatus.PAID);
        order.setPaymentMethod(PaymentMethodType.WEBPAY);
        order.setFulfillmentStatus(FulfillmentStatus.UNFULFILLED);
        return orderRepository.save(order);
    }

    private OutboxMessage row(Order order, OutboxType type, Long companyId, OutboxStatus status, String lastError) {
        return outboxRepository.save(OutboxMessage.builder()
                .order(order)
                .type(type)
                .companyId(companyId)
                .dedupKey(type + ":" + order.getId() + ":" + nextSeq())
                .status(status)
                .attempts(1)
                .lastError(lastError)
                .nextAttemptAt(Instant.now())
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build());
    }

    private MockHttpServletRequestBuilder admin(MockHttpServletRequestBuilder request, Store store, String token) {
        return request.header("Host", hostHeader(store)).header("Authorization", "Bearer " + token);
    }
}
