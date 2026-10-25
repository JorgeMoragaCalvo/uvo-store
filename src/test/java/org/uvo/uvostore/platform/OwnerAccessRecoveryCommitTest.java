package org.uvo.uvostore.platform;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.uvo.uvostore.entity.security.User;
import org.uvo.uvostore.entity.tenant.Store;
import org.uvo.uvostore.repository.OrderRepository;
import org.uvo.uvostore.repository.PermissionRepository;
import org.uvo.uvostore.repository.RoleRepository;
import org.uvo.uvostore.repository.StoreRepository;
import org.uvo.uvostore.repository.UserRepository;
import org.uvo.uvostore.service.platform.StoreLifecycleService;
import org.uvo.uvostore.service.security.AdminInvitationMailer;
import org.uvo.uvostore.support.CommittedFixtures;
import org.uvo.uvostore.support.WithPosIntegration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * PROD-23. La invitación de la recuperación de acceso sale <b>después del commit</b>, igual que la del alta: si la
 * operación se deshiciera, el dueño recibiría un enlace con un token que no existe. Solo se ve con transacciones
 * confirmadas de verdad; {@link #cleanUp} borra lo que quedó.
 *
 * <p>Las mismas anotaciones y el mismo espía que {@code StoreOnboardingCommitTest}, para compartir su contexto.
 */
@WithPosIntegration
@AutoConfigureMockMvc
class OwnerAccessRecoveryCommitTest {

    @Autowired
    private StoreLifecycleService lifecycle;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private JdbcTemplate jdbc;
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
    @MockitoSpyBean
    private AdminInvitationMailer mailer;

    private CommittedFixtures fixtures;
    private Store store;
    private User owner;

    @BeforeEach
    void setUp() {
        fixtures = new CommittedFixtures(transactionManager, jdbc, storeRepository, userRepository, roleRepository,
                permissionRepository, orderRepository, passwordEncoder);
        store = fixtures.store("rec-commit");
        owner = fixtures.admin(store, "rec-commit-owner");
        jdbc.update("UPDATE stores SET owner_user_id = ? WHERE id = ?", owner.getId(), store.getId());
    }

    @AfterEach
    void cleanUp() {
        fixtures.purgeStore(store.getId());
    }

    @Test
    @DisplayName("La invitación sale una vez, después del commit, con el token nuevo")
    void theInvitationIsSentAfterCommit() {
        lifecycle.recoverOwnerAccess(store.getId(), null);

        String token = userRepository.findById(owner.getId()).orElseThrow().getInvitationToken();
        assertThat(token).isNotNull();
        verify(mailer, times(1)).send(argThat((User invited) -> invited.getId().equals(owner.getId())
                && token.equals(invited.getInvitationToken())), argThat((Store target) -> target.getId().equals(store.getId())));
    }

    @Test
    @DisplayName("Si la operación se deshace, la invitación no sale y la cuenta queda como estaba")
    void nothingIsSentWhenItRollsBack() {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            lifecycle.recoverOwnerAccess(store.getId(), null);
            status.setRollbackOnly();
        });

        verify(mailer, never()).send(any(), any());
        User unchanged = userRepository.findById(owner.getId()).orElseThrow();
        assertThat(unchanged.getPassword()).isNotNull();
        assertThat(unchanged.getInvitationToken()).isNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_events WHERE store_id = ?", Integer.class,
                store.getId())).isZero();
    }
}
