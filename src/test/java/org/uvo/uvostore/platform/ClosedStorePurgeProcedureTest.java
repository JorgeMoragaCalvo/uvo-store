package org.uvo.uvostore.platform;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.uvo.uvostore.entity.security.User;
import org.uvo.uvostore.entity.settings.StoreSettings;
import org.uvo.uvostore.entity.tenant.Store;
import org.uvo.uvostore.repository.OrderRepository;
import org.uvo.uvostore.repository.PermissionRepository;
import org.uvo.uvostore.repository.RoleRepository;
import org.uvo.uvostore.repository.StoreRepository;
import org.uvo.uvostore.repository.StoreSettingsRepository;
import org.uvo.uvostore.repository.UserRepository;
import org.uvo.uvostore.service.platform.StoreClosureService;
import org.uvo.uvostore.service.platform.StoreLifecycleService;
import org.uvo.uvostore.service.platform.StoreStatusReason;
import org.uvo.uvostore.support.CommittedFixtures;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PROD-23. El procedimiento de exportación y purga de una tienda cerrada está en {@code CLAUDE.md}, y este test corre
 * <b>esos mismos bloques</b> (los lee entre sus marcadores): un procedimiento documentado que nadie ejecutó falla el día
 * que hace falta, con una clave foránea a medio camino.
 *
 * <p>Confirma de verdad: la purga borra con sentencias propias en su transacción, y la tienda tiene que estar cerrada
 * por el servicio (que también confirma). Crea una fila en cada tabla de la tienda, por JDBC, para que cada
 * {@code DELETE} y cada cascada tengan algo que borrar.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ClosedStorePurgeProcedureTest {

    private static final Path CLAUDE_MD = Path.of("CLAUDE.md");

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
    private StoreSettingsRepository storeSettingsRepository;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private StoreLifecycleService lifecycle;
    @Autowired
    private StoreClosureService closure;

    private CommittedFixtures fixtures;
    private final List<Long> stores = new ArrayList<>();

    @BeforeEach
    void setUp() {
        fixtures = new CommittedFixtures(transactionManager, jdbc, storeRepository, userRepository, roleRepository,
                permissionRepository, orderRepository, passwordEncoder);
    }

    /** Si algo quedó (el test falló a medias), lo borra el mismo procedimiento, con la tienda forzada a cerrada. */
    @AfterEach
    void cleanUp() throws IOException {
        for (Long storeId : stores) {
            if (count("SELECT count(*) FROM stores WHERE id = ?", storeId) > 0) {
                jdbc.update("UPDATE stores SET status = 'CLOSED' WHERE id = ?", storeId);
                runPurge(storeId);
            }
            runAuditPurge(storeId);
        }
    }

    @Test
    @DisplayName("Exportar y purgar una tienda cerrada con el procedimiento de CLAUDE.md la deja sin una sola fila")
    void theDocumentedProcedureLeavesNothingBehind() throws Exception {
        Filled filled = filledStore();
        lifecycle.suspend(filled.storeId, StoreStatusReason.MERCHANT_REQUEST);
        closure.close(filled.storeId, filled.slug, StoreStatusReason.MERCHANT_REQUEST);

        // La exportación: cada consulta corre contra el esquema de hoy y devuelve lo que la tienda tenía.
        List<String> exports = exportQueries();
        assertThat(exports).hasSize(6);
        for (String query : exports) {
            assertThat(jdbc.queryForList(withStore(query, filled.storeId))).as(query).isNotEmpty();
        }

        List<String> imagePaths = runPurge(filled.storeId);
        assertThat(imagePaths).contains("products/p.png", "products/gallery.png", "variations/v.png",
                "categories/c.png", "banners/b.png", "banners/mobile/b.png", "logos/l.png", "favicons/f.png",
                "avatars/a.png");
        runAuditPurge(filled.storeId);

        for (String table : storeTables()) {
            assertThat(count("SELECT count(*) FROM " + table + " WHERE store_id = ?", filled.storeId))
                    .as(table).isZero();
        }
        assertThat(count("SELECT count(*) FROM stores WHERE id = ?", filled.storeId)).isZero();
        assertThat(count("SELECT count(*) FROM user_roles WHERE user_id = ?", filled.ownerId)).isZero();
        assertThat(count("SELECT count(*) FROM role_permissions WHERE role_id = ?", filled.roleId)).isZero();
        assertThat(count("SELECT count(*) FROM order_items WHERE order_id = ?", filled.orderId)).isZero();
        assertThat(count("SELECT count(*) FROM outbox_messages WHERE order_id = ?", filled.orderId)).isZero();
        assertThat(count("SELECT count(*) FROM sync_webhook_logs WHERE company_id = ?", filled.companyId)).isZero();
        assertThat(count("SELECT count(*) FROM pos_webhook_receipts WHERE company_id = ?", filled.companyId)).isZero();
    }

    @Test
    @DisplayName("La purga no toca una tienda que no está cerrada")
    void thePurgeIgnoresAStoreThatIsNotClosed() throws Exception {
        Store store = fixtures.store("purga-activa");
        stores.add(store.getId());
        jdbc.update("INSERT INTO settings (store_id, key, value) VALUES (?, 'store_name', 'Sigue viva')", store.getId());

        runPurge(store.getId());

        assertThat(count("SELECT count(*) FROM stores WHERE id = ?", store.getId())).isOne();
        assertThat(count("SELECT count(*) FROM settings WHERE store_id = ?", store.getId())).isOne();
        jdbc.update("DELETE FROM settings WHERE store_id = ?", store.getId());
        jdbc.update("DELETE FROM stores WHERE id = ?", store.getId());
    }

    @Test
    @DisplayName("Toda tabla con store_id aparece en la purga: una tabla nueva obliga a decidir qué hacer con ella")
    void everyStoreTableIsNamedInThePurge() throws IOException {
        String purge = block("prod23-purge") + block("prod23-audit-purge");

        for (String table : storeTables()) {
            assertThat(Pattern.compile("\\b" + table + "\\b").matcher(purge).find())
                    .as("la purga de CLAUDE.md no nombra la tabla %s", table).isTrue();
        }
    }

    // --- el procedimiento -------------------------------------------------------------------------

    /** Corre la purga en una transacción, como en psql; devuelve las rutas de imágenes que lista primero. */
    private List<String> runPurge(Long storeId) throws IOException {
        List<String> statements = statements(block("prod23-purge"), storeId);
        List<String> paths = new ArrayList<>();
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            for (String statement : statements) {
                if (statement.startsWith("SELECT image_path")) {
                    paths.addAll(jdbc.queryForList(statement, String.class));
                } else {
                    jdbc.execute(statement);
                }
            }
        });
        return paths;
    }

    private void runAuditPurge(Long storeId) throws IOException {
        for (String statement : statements(block("prod23-audit-purge"), storeId)) {
            jdbc.execute(statement);
        }
    }

    /**
     * Las consultas de la exportación. Con {@code COPY ... TO STDOUT \g archivo} y no con {@code \copy}: psql no reemplaza
     * {@code :store_id} dentro de {@code \copy}. Este test no lo veía, porque reemplaza la variable él mismo; lo vio la
     * prueba manual con psql. Una línea {@code \copy} ya no calza con el patrón, y el conteo de seis falla.
     */
    private List<String> exportQueries() throws IOException {
        List<String> queries = new ArrayList<>();
        Matcher matcher = Pattern.compile("^COPY \\((.*)\\) TO STDOUT WITH CSV HEADER \\\\g \\S+\\.csv$", Pattern.MULTILINE)
                .matcher(block("prod23-export"));
        while (matcher.find()) {
            queries.add(matcher.group(1));
        }
        return queries;
    }

    /** Las sentencias de un bloque, sin comentarios, sin BEGIN/COMMIT (los pone la transacción) ni comandos de psql. */
    private static List<String> statements(String block, Long storeId) {
        StringBuilder sql = new StringBuilder();
        for (String line : block.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("--") || trimmed.startsWith("\\")
                    || trimmed.equals("BEGIN;") || trimmed.equals("COMMIT;")) {
                continue;
            }
            sql.append(line).append('\n');
        }
        List<String> statements = new ArrayList<>();
        for (String statement : sql.toString().split(";\\s*\n")) {
            if (!statement.isBlank()) {
                statements.add(withStore(statement.trim().replaceAll(";$", ""), storeId));
            }
        }
        return statements;
    }

    private static String withStore(String sql, Long storeId) {
        return sql.replace(":store_id", String.valueOf(storeId));
    }

    private static String block(String marker) throws IOException {
        String doc = Files.readString(CLAUDE_MD, StandardCharsets.UTF_8).replace("\r\n", "\n");
        Matcher matcher = Pattern.compile("<!-- " + marker + ":start -->\\s*```sql\\n(.*?)```\\s*<!-- " + marker + ":end -->",
                Pattern.DOTALL).matcher(doc);
        assertThat(matcher.find()).as("bloque %s en CLAUDE.md", marker).isTrue();
        return matcher.group(1);
    }

    private List<String> storeTables() {
        return jdbc.queryForList("SELECT table_name FROM information_schema.columns "
                + "WHERE table_schema = 'public' AND column_name = 'store_id' ORDER BY table_name", String.class);
    }

    private long count(String sql, Object... args) {
        Long value = jdbc.queryForObject(sql, Long.class, args);
        return value == null ? 0 : value;
    }

    // --- una tienda con una fila en cada tabla ----------------------------------------------------

    private record Filled(Long storeId, String slug, Long ownerId, Long roleId, Long orderId, Long companyId) {
    }

    private Filled filledStore() {
        Store store = fixtures.store("purga");
        stores.add(store.getId());
        long id = store.getId();
        long seq = System.nanoTime();
        User owner = fixtures.admin(store, "purga-dueno");
        Long roleId = jdbc.queryForObject("SELECT role_id FROM user_roles WHERE user_id = ?", Long.class, owner.getId());
        jdbc.update("UPDATE users SET avatar = 'avatars/a.png' WHERE id = ?", owner.getId());
        jdbc.update("UPDATE stores SET owner_user_id = ?, domain = ? WHERE id = ?", owner.getId(), "purga-" + seq + ".example.com", id);

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            StoreSettings branding = new StoreSettings();
            branding.setStore(storeRepository.getReferenceById(id));
            branding.setStoreName("Purga");
            branding.setStoreLogo("logos/l.png");
            branding.setStoreFavicon("favicons/f.png");
            storeSettingsRepository.save(branding);
        });
        jdbc.update("INSERT INTO settings (store_id, key, value) VALUES (?, 'stripe_secret_key', 'cifrado')", id);
        jdbc.update("INSERT INTO home_banners (store_id, title, image, mobile_image, cta_text, cta_new_tab, text_position, "
                + "text_color, overlay_opacity, active, sort_order) VALUES (?, 'B', 'banners/b.png', 'banners/mobile/b.png', "
                + "'Ver', false, 'left', '#ffffff', 0, true, 0)", id);
        jdbc.update("INSERT INTO payment_gateway_configs (store_id, gateway, enabled, credentials) VALUES (?, 'WEBPAY', true, 'x')", id);

        long companyId = 860_000_000L + (seq % 1_000_000);
        jdbc.update("INSERT INTO pos_connections (store_id, company_name, company_id, api_url, api_key, webhook_secret, is_active) "
                + "VALUES (?, 'Empresa', ?, 'http://pos', 'k', 's', true)", id, companyId);
        jdbc.update("INSERT INTO sync_webhook_logs (company_id, event_type, status) VALUES (?, 'stock.updated', 'SUCCESS')", companyId);
        jdbc.update("INSERT INTO pos_webhook_receipts (company_id, signature) VALUES (?, ?)", companyId, "sig-" + seq);

        long categoryId = insert("INSERT INTO categories (store_id, name, slug, active, is_featured, sort_order, products_count, image) "
                + "VALUES (?, 'Cat', ?, true, false, 0, 0, 'categories/c.png') RETURNING id", id, "cat-" + seq);
        long productId = insert("INSERT INTO products (store_id, category_id, name, slug, sku, product_type, price, stock, "
                + "manage_stock, active, featured, is_featured, is_new, is_on_sale, sort_order, views_count, sales_count, featured_image) "
                + "VALUES (?, ?, 'Prod', ?, ?, 'VARIABLE', 1000, 5, true, true, false, false, false, false, 0, 0, 0, 'products/p.png') "
                + "RETURNING id", id, categoryId, "prod-" + seq, "SKU-" + seq);
        jdbc.update("INSERT INTO product_images (store_id, product_id, image_path, sort_order, is_featured, type) "
                + "VALUES (?, ?, 'products/gallery.png', 0, false, 'GALLERY')", id, productId);
        long attributeId = insert("INSERT INTO attributes (store_id, name, slug, type) VALUES (?, 'Talla', ?, 'SELECT') RETURNING id",
                id, "talla-" + seq);
        long valueId = insert("INSERT INTO attribute_values (store_id, attribute_id, value, slug, sort_order) "
                + "VALUES (?, ?, 'M', 'm', 0) RETURNING id", id, attributeId);
        long variationId = insert("INSERT INTO product_variations (store_id, product_id, sku, price, stock, active, image) "
                + "VALUES (?, ?, ?, 1000, 5, true, 'variations/v.png') RETURNING id", id, productId, "SKU-" + seq + "-M");
        jdbc.update("INSERT INTO product_variation_attributes (store_id, product_variation_id, attribute_id, attribute_value_id) "
                + "VALUES (?, ?, ?, ?)", id, variationId, attributeId, valueId);
        long mappingId = insert("INSERT INTO product_sync_mapping (product_id, external_id, external_sku, company_id, sync_status, "
                + "sync_direction, sync_stock, sync_price, sync_name, sync_description) "
                + "VALUES (?, ?, 'EXT', ?, 'ACTIVE', 'FROM_POS', true, true, false, false) RETURNING id", productId, seq, companyId);
        jdbc.update("INSERT INTO product_variation_sync_mapping (mapping_id, product_variation_id, external_variant_id, external_sku) "
                + "VALUES (?, ?, ?, 'EXT-M')", mappingId, variationId, seq);

        long customerId = insert("INSERT INTO customers (store_id, email, first_name, last_name, account_status, password) "
                + "VALUES (?, ?, 'Cli', 'Ente', 'ACTIVE', 'hash') RETURNING id", id, "cliente-" + seq + "@test.local");
        jdbc.update("INSERT INTO shipping_addresses (store_id, customer_id, first_name, last_name, address_line1, city, state, "
                + "postal_code, country, phone, is_default) VALUES (?, ?, 'Cli', 'Ente', 'Calle 1', 'Santiago', 'RM', '000', 'CL', "
                + "'+56', true)", id, customerId);
        long zoneId = insert("INSERT INTO shipping_zones (store_id, name, is_active, sort_order) VALUES (?, 'Zona', true, 0) RETURNING id", id);
        long methodId = insert("INSERT INTO shipping_methods (store_id, name, code, type, has_api_integration, is_active, sort_order) "
                + "VALUES (?, 'Envío', ?, 'COURIER', false, true, 0) RETURNING id", id, "envio-" + seq);
        jdbc.update("INSERT INTO shipping_rates (store_id, shipping_method_id, shipping_zone_id, name, rate_type, is_active, sort_order) "
                + "VALUES (?, ?, ?, 'Tarifa', 'FLAT', true, 0)", id, methodId, zoneId);
        long couponId = insert("INSERT INTO coupons (store_id, code, name, type, value, times_used, is_active) "
                + "VALUES (?, ?, 'Cupón', 'FIXED', 100, 1, true) RETURNING id", id, "CUP-" + seq);

        long orderId = insert("INSERT INTO orders (store_id, order_number, customer_email, customer_first_name, customer_last_name, "
                + "subtotal, discount_amount, shipping_cost, tax_amount, total, status, payment_status, fulfillment_status, "
                + "customer_id, coupon_id, shipping_method_id, shipping_zone_id, stock_applied) "
                + "VALUES (?, ?, 'cliente@test.local', 'Cli', 'Ente', 1000, 100, 0, 0, 900, 'DELIVERED', 'PAID', 'FULFILLED', "
                + "?, ?, ?, ?, true) RETURNING id", id, "ORD-PURGA-" + seq, customerId, couponId, methodId, zoneId);
        jdbc.update("INSERT INTO order_items (order_id, product_id, product_variation_id, product_name, product_sku, quantity, price, "
                + "subtotal, tax_amount) VALUES (?, ?, ?, 'Prod', 'SKU', 1, 1000, 1000, 0)", orderId, productId, variationId);
        jdbc.update("INSERT INTO order_status_history (order_id, status, user_id) VALUES (?, 'DELIVERED', ?)", orderId, owner.getId());
        jdbc.update("INSERT INTO coupon_usages (coupon_id, order_id, customer_id, discount_amount) VALUES (?, ?, ?, 100)",
                couponId, orderId, customerId);
        jdbc.update("INSERT INTO order_refunds (order_id, amount, type, status, user_id) VALUES (?, 100, 'EXTERNAL', 'COMPLETED', ?)",
                orderId, owner.getId());
        jdbc.update("INSERT INTO outbox_messages (order_id, type, dedup_key, status, company_id) VALUES (?, 'POS_ORDER', ?, 'SENT', ?)",
                orderId, "purga:" + seq, companyId);

        // La auditoría la escriben la suspensión y el cierre, después.
        assertThat(storeTables()).filteredOn(table -> !table.equals("audit_events")).allSatisfy(table -> assertThat(
                count("SELECT count(*) FROM " + table + " WHERE store_id = ?", id))
                .as("la tienda de prueba no tiene filas en %s: agrégalas aquí", table).isPositive());
        return new Filled(id, store.getSlug(), owner.getId(), roleId, orderId, companyId);
    }

    private long insert(String sql, Object... args) {
        Long id = jdbc.queryForObject(sql, Long.class, args);
        return id == null ? 0 : id;
    }
}
