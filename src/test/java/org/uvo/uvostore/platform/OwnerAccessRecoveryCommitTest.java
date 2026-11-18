package org.uvo.uvostore.platform;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.uvo.uvostore.entity.outbox.enums.OutboxStatus;
import org.uvo.uvostore.entity.outbox.enums.OutboxType;
import org.uvo.uvostore.entity.security.User;
import org.uvo.uvostore.entity.tenant.Store;
import org.uvo.uvostore.repository.OrderRepository;
import org.uvo.uvostore.repository.OutboxMessageRepository;
import org.uvo.uvostore.repository.PermissionRepository;
import org.uvo.uvostore.repository.RoleRepository;
import org.uvo.uvostore.repository.StoreRepository;
import org.uvo.uvostore.repository.UserRepository;
import org.uvo.uvostore.service.platform.StoreLifecycleService;
import org.uvo.uvostore.service.outbox.AccessTokenHash;
import org.uvo.uvostore.support.CommittedFixtures;
import org.uvo.uvostore.support.WithPosIntegration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PROD-23. La invitación de la recuperación de acceso no puede salir si la operación se deshace: el dueño recibiría un
 * enlace con un token que no existe. IMP-06: ahora es una fila del outbox escrita en la misma transacción, así que
 * existe si y solo si la recuperación se confirmó. Solo se ve con transacciones confirmadas de verdad;
 * {@link #cleanUp} borra lo que quedó.
 *
 * <p>Las mismas anotaciones que {@code StoreOnboardingCommitTest}, para compartir su contexto.
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
    @Autowired
    private OutboxMessageRepository outboxRepository;

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
    @DisplayName("Con el commit queda una sola fila del correo, con la huella del token nuevo")
    void theInvitationIsQueuedWithTheCommit() {
        lifecycle.recoverOwnerAccess(store.getId(), null);

        String token = userRepository.findById(owner.getId()).orElseThrow().getInvitationToken();
        assertThat(token).isNotNull();
        assertThat(outboxRepository.findByUserIdOrderByIdDesc(owner.getId())).singleElement().satisfies(message -> {
            assertThat(message.getType()).isEqualTo(OutboxType.ADMIN_INVITATION_EMAIL);
            assertThat(message.getStatus()).isEqualTo(OutboxStatus.PENDING);
            assertThat(message.getTokenHash()).isEqualTo(AccessTokenHash.of(token)).isNotEqualTo(token);
        });
        // La fila no guarda el token en ninguna columna.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_messages WHERE user_id = ? "
                + "AND (dedup_key LIKE ? OR coalesce(last_error, '') LIKE ?)", Integer.class, owner.getId(),
                "%" + token + "%", "%" + token + "%")).isZero();
    }

    @Test
    @DisplayName("Si la operación se deshace, la invitación no sale y la cuenta queda como estaba")
    void nothingIsSentWhenItRollsBack() {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            lifecycle.recoverOwnerAccess(store.getId(), null);
            status.setRollbackOnly();
        });

        assertThat(outboxRepository.findByUserIdOrderByIdDesc(owner.getId())).isEmpty();
        User unchanged = userRepository.findById(owner.getId()).orElseThrow();
        assertThat(unchanged.getPassword()).isNotNull();
        assertThat(unchanged.getInvitationToken()).isNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_events WHERE store_id = ?", Integer.class,
                store.getId())).isZero();
    }
}
