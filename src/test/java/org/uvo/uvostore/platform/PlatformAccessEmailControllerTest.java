package org.uvo.uvostore.platform;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
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
import org.uvo.uvostore.service.outbox.AccessTokenHash;
import org.uvo.uvostore.support.AuditRows;
import org.uvo.uvostore.support.IntegrationTestSupport;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * IMP-06. Los correos de acceso fallidos vistos y recuperados desde la plataforma ({@code /api/platform/access-emails}),
 * y desde el panel de la tienda («Envíos fallidos»). La entrega después del reintento, con commit real, está en
 * {@code AccessEmailOutboxTest}; aquí todo se deshace.
 */
class PlatformAccessEmailControllerTest extends IntegrationTestSupport {

    @Value("${app.platform-api-key}")
    private String platformKey;
    @Autowired
    private OutboxMessageRepository outboxRepository;
    @Autowired
    private OrderRepository orderRepository;

    @Test
    @DisplayName("Sin la clave de plataforma no hay nada que ver")
    void requiresThePlatformKey() throws Exception {
        mockMvc.perform(get("/api/platform/access-emails/failed"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Lista los correos de acceso fallidos de todas las tiendas, o de una, y nunca las filas de orden")
    void listsFailedAccessEmailsAcrossStores() throws Exception {
        Store first = createStore("acceso-a");
        Store second = createStore("acceso-b");
        OutboxMessage a = accessRow(createAdmin(first, "acceso-a"), OutboxStatus.FAILED);
        OutboxMessage b = accessRow(createAdmin(second, "acceso-b"), OutboxStatus.FAILED);
        accessRow(createAdmin(first, "acceso-a-ok"), OutboxStatus.SENT);
        OutboxMessage orderRow = orderRow(first);

        mockMvc.perform(platform(get("/api/platform/access-emails/failed")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].id").value(hasItem(a.getId().intValue())))
                .andExpect(jsonPath("$.content[*].id").value(hasItem(b.getId().intValue())))
                .andExpect(jsonPath("$.content[*].id").value(not(hasItem(orderRow.getId().intValue()))));
        mockMvc.perform(platform(get("/api/platform/access-emails/failed").param("storeId", first.getId().toString())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(a.getId()))
                .andExpect(jsonPath("$.content[0].storeId").value(first.getId()))
                .andExpect(jsonPath("$.content[0].channel").value("ACCESS"))
                .andExpect(jsonPath("$.content[0].recipientEmail").value(a.getUser().getEmail()))
                .andExpect(jsonPath("$.content[0].orderId").doesNotExist())
                // Ni el token ni su huella salen por la API.
                .andExpect(jsonPath("$.content[0].tokenHash").doesNotExist());
    }

    @Test
    @DisplayName("Reintentar pide nota, vuelve a la cola, queda auditado contra el usuario; una fila de orden da 404")
    void retryRequeuesAndAudits() throws Exception {
        Store store = createStore("acceso-retry");
        User admin = createAdmin(store, "acceso-retry");
        OutboxMessage row = accessRow(admin, OutboxStatus.FAILED);

        mockMvc.perform(platform(post("/api/platform/access-emails/" + row.getId() + "/retry"))
                        .contentType("application/json").content("{\"note\":\" \"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(platform(post("/api/platform/access-emails/" + row.getId() + "/retry"))
                        .header("X-Platform-Operator", "op-correo")
                        .contentType("application/json").content("{\"note\":\"El SMTP volvió\"}"))
                .andExpect(status().isNoContent());

        assertThat(jdbcTemplate.queryForObject("SELECT status FROM outbox_messages WHERE id = ?", String.class,
                row.getId())).isEqualTo("PENDING");
        AuditRows.Row audit = AuditRows.single(jdbcTemplate, store.getId(), "OUTBOX_RETRY");
        assertThat(audit.actorType()).isEqualTo("PLATFORM");
        assertThat(audit.actorLabel()).isEqualTo("op-correo");
        assertThat(audit.targetType()).isEqualTo("USER");
        assertThat(audit.targetId()).isEqualTo(admin.getId());

        OutboxMessage orderRow = orderRow(store);
        mockMvc.perform(platform(post("/api/platform/access-emails/" + orderRow.getId() + "/retry"))
                        .contentType("application/json").content("{\"note\":\"No es de acceso\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("Resolver lo cierra sin reenviar")
    void resolveClosesWithoutResending() throws Exception {
        Store store = createStore("acceso-resolve");
        OutboxMessage row = accessRow(createAdmin(store, "acceso-resolve"), OutboxStatus.FAILED);

        mockMvc.perform(platform(post("/api/platform/access-emails/" + row.getId() + "/resolve"))
                        .contentType("application/json").content("{\"note\":\"Se le dio acceso por recuperación\"}"))
                .andExpect(status().isNoContent());

        assertThat(jdbcTemplate.queryForMap("SELECT status, last_error FROM outbox_messages WHERE id = ?", row.getId()))
                .containsEntry("status", "SKIPPED")
                .containsEntry("last_error", "Resuelta a mano: Se le dio acceso por recuperación");
    }

    @Test
    @DisplayName("El panel de la tienda también ve sus correos de acceso fallidos, sin orden y con el destinatario")
    void theStorePanelSeesItsFailedAccessEmails() throws Exception {
        Store store = createStore("acceso-panel");
        User viewer = createAdmin(store, "acceso-panel");
        String token = loginAdmin(store, viewer);
        OutboxMessage row = accessRow(createAdmin(store, "acceso-panel-invitado"), OutboxStatus.FAILED);

        mockMvc.perform(get("/api/admin/outbox/failed").header("Host", hostHeader(store))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(row.getId()))
                .andExpect(jsonPath("$.content[0].type").value("ADMIN_INVITATION_EMAIL"))
                .andExpect(jsonPath("$.content[0].orderNumber").doesNotExist())
                .andExpect(jsonPath("$.content[0].recipientEmail").value(row.getUser().getEmail()));
        mockMvc.perform(post("/api/admin/outbox/" + row.getId() + "/resolve").header("Host", hostHeader(store))
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json").content("{\"note\":\"Reenviada desde Usuarios\"}"))
                .andExpect(status().isNoContent());
        assertThat(auditRow(store, "OUTBOX_RESOLVE").targetType()).isEqualTo("USER");
    }

    // --- fixtures ----------------------------------------------------------------------------------------------------

    private MockHttpServletRequestBuilder platform(MockHttpServletRequestBuilder request) {
        return request.header("X-Platform-Key", platformKey);
    }

    private OutboxMessage accessRow(User user, OutboxStatus status) {
        return outboxRepository.save(OutboxMessage.builder()
                .store(user.getStore())
                .user(user)
                .tokenHash(AccessTokenHash.of("token-de-prueba-" + nextSeq()))
                .type(OutboxType.ADMIN_INVITATION_EMAIL)
                .dedupKey("acceso-prueba:" + nextSeq())
                .status(status)
                .attempts(3)
                .lastError(status == OutboxStatus.FAILED ? "Sin éxito tras 48 h: Fallo de envío" : null)
                .nextAttemptAt(Instant.now())
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build());
    }

    private OutboxMessage orderRow(Store store) {
        Order order = new Order();
        order.setStore(store);
        order.setOrderNumber("ORD-ACCESO-" + nextSeq());
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
        order = orderRepository.save(order);
        return outboxRepository.save(OutboxMessage.builder()
                .order(order)
                .store(store)
                .type(OutboxType.ORDER_CONFIRMATION_EMAIL)
                .dedupKey("orden-prueba:" + nextSeq())
                .status(OutboxStatus.FAILED)
                .attempts(1)
                .lastError("Rechazado: dirección inválida")
                .nextAttemptAt(Instant.now())
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build());
    }
}
