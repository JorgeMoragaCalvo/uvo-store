package org.uvo.uvostore.support;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.uvo.uvostore.entity.order.Order;
import org.uvo.uvostore.entity.order.enums.FulfillmentStatus;
import org.uvo.uvostore.entity.order.enums.OrderStatus;
import org.uvo.uvostore.entity.order.enums.PaymentMethodType;
import org.uvo.uvostore.entity.order.enums.PaymentStatus;
import org.uvo.uvostore.entity.security.Role;
import org.uvo.uvostore.entity.security.User;
import org.uvo.uvostore.entity.tenant.Store;
import org.uvo.uvostore.entity.tenant.enums.StoreStatus;
import org.uvo.uvostore.repository.OrderRepository;
import org.uvo.uvostore.repository.PermissionRepository;
import org.uvo.uvostore.repository.RoleRepository;
import org.uvo.uvostore.repository.StoreRepository;
import org.uvo.uvostore.repository.UserRepository;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Consumer;

/**
 * PROD-18. Datos confirmados de verdad, para los tests que necesitan que una transacción confirme (el login fallido,
 * el reembolso). Bajo {@link IntegrationTestSupport} todo se deshace, y ahí no se ve qué sobrevive a un rollback.
 *
 * <p>Lo que se crea aquí queda en la base local hasta {@link #purgeStore}: cada test lo llama en su
 * {@code @AfterEach}. No es un {@code @Component} a propósito: el escaneo de la aplicación lo recogería en todos los
 * contextos.
 */
public final class CommittedFixtures {

    public static final String PASSWORD = "password123";

    private final TransactionTemplate tx;
    private final JdbcTemplate jdbc;
    private final StoreRepository storeRepository;
    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final PermissionRepository permissionRepository;
    private final OrderRepository orderRepository;
    private final PasswordEncoder passwordEncoder;

    public CommittedFixtures(PlatformTransactionManager transactionManager, JdbcTemplate jdbc,
                             StoreRepository storeRepository, UserRepository userRepository,
                             RoleRepository roleRepository, PermissionRepository permissionRepository,
                             OrderRepository orderRepository, PasswordEncoder passwordEncoder) {
        this.tx = new TransactionTemplate(transactionManager);
        this.jdbc = jdbc;
        this.storeRepository = storeRepository;
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.permissionRepository = permissionRepository;
        this.orderRepository = orderRepository;
        this.passwordEncoder = passwordEncoder;
    }

    public Store store(String prefix) {
        return tx.execute(status -> storeRepository.save(Store.builder()
                .name(prefix + " (confirmada)")
                .slug(prefix + "-" + System.nanoTime())
                .status(StoreStatus.ACTIVE)
                .build()));
    }

    /** Un admin con todos los permisos y {@link #PASSWORD}; {@code customizer} lo ajusta antes de guardarlo. */
    public User admin(Store store, String prefix, Consumer<User> customizer) {
        return tx.execute(status -> {
            Role role = new Role();
            role.setStore(store);
            role.setName("Rol " + prefix + "-" + System.nanoTime());
            role.setPermissions(new HashSet<>(permissionRepository.findAll()));
            Role savedRole = roleRepository.save(role);
            User user = User.builder()
                    .store(store)
                    .name("Admin " + prefix)
                    .email(prefix + "-" + System.nanoTime() + "@test.local")
                    .password(passwordEncoder.encode(PASSWORD))
                    .isActive(true)
                    .isAdmin(true)
                    .roles(new HashSet<>(Set.of(savedRole)))
                    .build();
            customizer.accept(user);
            return userRepository.save(user);
        });
    }

    public User admin(Store store, String prefix) {
        return admin(store, prefix, user -> { });
    }

    /** Una orden pagada, sin ítems: lo que hace falta para reembolsarla. */
    public Order paidOrder(Store store, PaymentMethodType method, BigDecimal total) {
        return tx.execute(status -> {
            Order order = new Order();
            order.setStore(store);
            order.setOrderNumber("ORD-AUD-" + System.nanoTime());
            order.setCustomerEmail("comprador@test.local");
            order.setCustomerFirstName("Test");
            order.setCustomerLastName("Comprador");
            order.setSubtotal(total);
            order.setDiscountAmount(BigDecimal.ZERO);
            order.setShippingCost(BigDecimal.ZERO);
            order.setTaxAmount(BigDecimal.ZERO);
            order.setTotal(total);
            order.setStatus(OrderStatus.PROCESSING);
            order.setPaymentStatus(PaymentStatus.PAID);
            order.setPaymentMethod(method);
            order.setFulfillmentStatus(FulfillmentStatus.UNFULFILLED);
            return orderRepository.save(order);
        });
    }

    /** Borra la tienda y todo lo que cuelga de ella, auditoría incluida, en orden de claves foráneas. */
    public void purgeStore(Long storeId) {
        if (storeId == null) {
            return;
        }
        jdbc.update("UPDATE stores SET owner_user_id = NULL WHERE id = ?", storeId);
        jdbc.update("DELETE FROM audit_events WHERE store_id = ?", storeId);
        // Los hijos de una orden (ítems, historial, reembolsos, outbox) caen en cascada.
        jdbc.update("DELETE FROM orders WHERE store_id = ?", storeId);
        jdbc.update("DELETE FROM products WHERE store_id = ?", storeId);
        jdbc.update("DELETE FROM categories WHERE store_id = ?", storeId);
        jdbc.update("DELETE FROM settings WHERE store_id = ?", storeId);
        jdbc.update("DELETE FROM store_settings WHERE store_id = ?", storeId);
        jdbc.update("DELETE FROM pos_connections WHERE store_id = ?", storeId);
        jdbc.update("DELETE FROM user_roles WHERE user_id IN (SELECT id FROM users WHERE store_id = ?)", storeId);
        jdbc.update("DELETE FROM role_permissions WHERE role_id IN (SELECT id FROM roles WHERE store_id = ?)", storeId);
        jdbc.update("DELETE FROM users WHERE store_id = ?", storeId);
        jdbc.update("DELETE FROM roles WHERE store_id = ?", storeId);
        jdbc.update("DELETE FROM stores WHERE id = ?", storeId);
    }
}
