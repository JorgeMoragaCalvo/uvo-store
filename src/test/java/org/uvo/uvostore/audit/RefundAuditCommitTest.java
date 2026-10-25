package org.uvo.uvostore.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.uvo.uvostore.entity.order.Order;
import org.uvo.uvostore.entity.order.enums.PaymentMethodType;
import org.uvo.uvostore.entity.security.User;
import org.uvo.uvostore.entity.tenant.Store;
import org.uvo.uvostore.repository.OrderRepository;
import org.uvo.uvostore.repository.PermissionRepository;
import org.uvo.uvostore.repository.RoleRepository;
import org.uvo.uvostore.repository.StoreRepository;
import org.uvo.uvostore.repository.UserRepository;
import org.uvo.uvostore.service.BusinessException;
import org.uvo.uvostore.service.payment.WebpayService;
import org.uvo.uvostore.support.AuditRows;
import org.uvo.uvostore.support.CommittedFixtures;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * PROD-18. El reembolso por pasarela, con transacciones confirmadas de verdad: la intención y el rechazo se registran
 * en las transacciones propias de {@code RefundIntentStore}, que sobreviven al rollback del reembolso.
 *
 * <p>Bajo {@code IntegrationTestSupport} este camino ni siquiera corre: la intención se inserta en otra transacción,
 * con una clave foránea a una orden que la del test todavía no confirmó. Webpay es un {@code @MockitoBean}.
 */
@SpringBootTest
@AutoConfigureMockMvc
class RefundAuditCommitTest {

    @MockitoBean
    private WebpayService webpayService;

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private StoreRepository storeRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private RoleRepository roleRepository;
    @Autowired
    private PermissionRepository permissionRepository;
    @Autowired
    private OrderRepository orderRepository;
    @Autowired
    private PasswordEncoder passwordEncoder;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private CommittedFixtures fixtures;
    private Store store;
    private Order order;
    private String token;

    @BeforeEach
    void setUp() throws Exception {
        fixtures = new CommittedFixtures(transactionManager, jdbc, storeRepository, userRepository, roleRepository,
                permissionRepository, orderRepository, passwordEncoder);
        store = fixtures.store("refund-audit");
        User admin = fixtures.admin(store, "refund-audit");
        order = fixtures.paidOrder(store, PaymentMethodType.WEBPAY, new BigDecimal("10000"));
        String response = mockMvc.perform(post("/api/admin/auth/login")
                        .header("Host", host())
                        .contentType("application/json")
                        .content("{\"email\":\"" + admin.getEmail() + "\",\"password\":\"" + CommittedFixtures.PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        token = objectMapper.readTree(response).get("token").asText();
    }

    @AfterEach
    void cleanUp() {
        fixtures.purgeStore(store.getId());
    }

    @Test
    @DisplayName("Un reembolso que sale bien deja la intención (ya confirmada al llamar a la pasarela) y el resultado")
    void aSuccessfulRefundLeavesTheRequestAndTheResult() throws Exception {
        AtomicInteger requestsSeenByTheGateway = new AtomicInteger(-1);
        when(webpayService.refund(eq(order.getId()), any())).thenAnswer(invocation -> {
            // Lo que ve otra conexión mientras la pasarela trabaja: la intención ya está confirmada. Si la transacción
            // local se pierde después de que la pasarela devolvió el dinero, esta es la fila que queda.
            requestsSeenByTheGateway.set(AuditRows.forStore(jdbc, store.getId(), "REFUND_REQUEST").size());
            return "NULLIFIED";
        });

        refund("{\"amount\":1000,\"reason\":\"motivo-privado-del-cliente\"}", 200);

        assertThat(requestsSeenByTheGateway.get()).isEqualTo(1);
        AuditRows.Row issued = AuditRows.single(jdbc, store.getId(), "REFUND_ISSUE");
        assertThat(issued.result()).isEqualTo("SUCCESS");
        assertThat(issued.targetType()).isEqualTo("ORDER");
        assertThat(issued.targetId()).isEqualTo(order.getId());
        assertThat(issued.json().path("amount").asText()).isEqualTo("1000");
        assertThat(issued.json().path("type").asText()).isEqualTo("PARTIAL");
        assertThat(issued.json().path("paymentMethod").asText()).isEqualTo("WEBPAY");
        assertThat(issued.json().path("closesOrder").asBoolean()).isFalse();
        // Ni el motivo (texto libre) ni la referencia de la pasarela.
        assertThat(issued.json().has("reason")).isFalse();
        assertThat(AuditRows.forStore(jdbc, store.getId()))
                .allSatisfy(row -> assertThat(String.valueOf(row.details())).doesNotContain("motivo-privado", "NULLIFIED"));
    }

    @Test
    @DisplayName("Si la pasarela rechaza: 400, el reembolso se deshace, y queda un FAILURE confirmado con la intención fallida")
    void aRejectedRefundLeavesACommittedFailure() throws Exception {
        when(webpayService.refund(eq(order.getId()), any()))
                .thenThrow(new BusinessException("Transbank rechazó la anulación"));

        refund("{\"amount\":1000}", 400);

        List<AuditRows.Row> issues = AuditRows.forStore(jdbc, store.getId(), "REFUND_ISSUE");
        assertThat(issues).extracting(AuditRows.Row::result).containsExactly("FAILURE");
        assertThat(issues.getFirst().actorType()).isEqualTo("ADMIN");
        assertThat(AuditRows.forStore(jdbc, store.getId(), "REFUND_REQUEST")).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT status FROM order_refunds WHERE order_id = ?", String.class, order.getId()))
                .isEqualTo("FAILED");
    }

    private void refund(String body, int expectedStatus) throws Exception {
        mockMvc.perform(post("/api/admin/orders/" + order.getId() + "/refund")
                        .header("Host", host())
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().is(expectedStatus));
    }

    private String host() {
        return store.getSlug() + ".localhost";
    }
}
