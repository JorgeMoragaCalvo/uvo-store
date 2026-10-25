package org.uvo.uvostore.audit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.uvo.uvostore.config.RequestIdFilter;
import org.uvo.uvostore.entity.security.User;
import org.uvo.uvostore.entity.tenant.Store;
import org.uvo.uvostore.repository.OrderRepository;
import org.uvo.uvostore.repository.PermissionRepository;
import org.uvo.uvostore.repository.RoleRepository;
import org.uvo.uvostore.repository.StoreRepository;
import org.uvo.uvostore.repository.UserRepository;
import org.uvo.uvostore.support.AuditRows;
import org.uvo.uvostore.support.CommittedFixtures;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * PROD-18. El login fallido de una cuenta existente queda en la auditoría aunque la petición termine en 401: el login
 * confirma su transacción en ese caso ({@code noRollbackFor}). Solo se ve confirmando de verdad; bajo
 * {@code IntegrationTestSupport} la transacción del test lo deshace todo igual.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AdminLoginAuditCommitTest {

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

    private CommittedFixtures fixtures;
    private Store store;

    @BeforeEach
    void setUp() {
        fixtures = new CommittedFixtures(transactionManager, jdbc, storeRepository, userRepository, roleRepository,
                permissionRepository, orderRepository, passwordEncoder);
        store = fixtures.store("login-audit");
    }

    @AfterEach
    void cleanUp() {
        fixtures.purgeStore(store.getId());
    }

    @Test
    @DisplayName("Una contraseña mala deja una fila FAILURE confirmada, a nombre de la cuenta y con el id de la petición")
    void aWrongPasswordLeavesACommittedFailure() throws Exception {
        User admin = fixtures.admin(store, "login-mala");

        MvcResult result = login(admin.getEmail(), "no-es-la-clave", 401);

        AuditRows.Row row = AuditRows.single(jdbc, store.getId(), "AUTH_LOGIN");
        assertThat(row.result()).isEqualTo("FAILURE");
        assertThat(row.actorType()).isEqualTo("ADMIN");
        assertThat(row.actorId()).isEqualTo(admin.getId());
        assertThat(row.actorLabel()).isEqualTo(admin.getEmail());
        assertThat(row.targetType()).isEqualTo("USER");
        assertThat(row.targetId()).isEqualTo(admin.getId());
        assertThat(row.json().path("reason").asText()).isEqualTo("BAD_PASSWORD");
        assertThat(row.requestId()).isEqualTo(result.getResponse().getHeader(RequestIdFilter.HEADER));
        assertThat(row.details()).doesNotContain("no-es-la-clave");
    }

    @Test
    @DisplayName("El motivo distingue la invitación sin aceptar y la cuenta desactivada")
    void theReasonTellsWhyItFailed() throws Exception {
        User invited = fixtures.admin(store, "login-invitado", user -> user.setPassword(null));
        User inactive = fixtures.admin(store, "login-inactivo", user -> user.setActive(false));

        login(invited.getEmail(), CommittedFixtures.PASSWORD, 401);
        login(inactive.getEmail(), CommittedFixtures.PASSWORD, 401);

        List<AuditRows.Row> rows = AuditRows.forStore(jdbc, store.getId(), "AUTH_LOGIN");
        assertThat(rows).extracting(row -> row.actorId() + ":" + row.json().path("reason").asText())
                .containsExactly(invited.getId() + ":NO_PASSWORD", inactive.getId() + ":INACTIVE");
    }

    @Test
    @DisplayName("Un correo que no existe no deja nada: es donde la gente escribe la contraseña por error")
    void anUnknownEmailLeavesNothing() throws Exception {
        login("password-pegada-en-el-correo@test.local", "lo-que-sea", 401);

        assertThat(AuditRows.forStore(jdbc, store.getId())).isEmpty();
    }

    @Test
    @DisplayName("Un login correcto deja una fila SUCCESS")
    void aSuccessfulLoginLeavesASuccess() throws Exception {
        User admin = fixtures.admin(store, "login-ok");

        login(admin.getEmail(), CommittedFixtures.PASSWORD, 200);

        AuditRows.Row row = AuditRows.single(jdbc, store.getId(), "AUTH_LOGIN");
        assertThat(row.result()).isEqualTo("SUCCESS");
        assertThat(row.actorId()).isEqualTo(admin.getId());
    }

    private MvcResult login(String email, String password, int expectedStatus) throws Exception {
        return mockMvc.perform(post("/api/admin/auth/login")
                        .header("Host", store.getSlug() + ".localhost")
                        .contentType("application/json")
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().is(expectedStatus))
                .andReturn();
    }
}
