package org.uvo.uvostore.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.transaction.TestTransaction;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;
import org.uvo.uvostore.entity.catalog.Category;
import org.uvo.uvostore.entity.catalog.Product;
import org.uvo.uvostore.entity.catalog.enums.ProductType;
import org.uvo.uvostore.entity.customer.Customer;
import org.uvo.uvostore.entity.customer.enums.AccountStatus;
import org.uvo.uvostore.entity.security.User;
import org.uvo.uvostore.entity.settings.Setting;
import org.uvo.uvostore.entity.tenant.Store;
import org.uvo.uvostore.repository.CategoryRepository;
import org.uvo.uvostore.repository.CustomerRepository;
import org.uvo.uvostore.repository.ProductRepository;
import org.uvo.uvostore.repository.SettingRepository;
import org.uvo.uvostore.repository.StoreRepository;
import org.uvo.uvostore.repository.UserRepository;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.util.concurrent.atomic.AtomicLong;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Every test method runs inside one Spring-managed transaction, auto-rolled-back at the end —
// fixtures created via the repositories below are visible to MockMvc-dispatched controller calls
// within the SAME test method (they share the same persistence context via propagation=REQUIRED),
// and nothing needs manual SQL cleanup afterward. AFTER_COMMIT listeners (POS notification, stock
// decrement) never fire under this setup since no real commit happens — that's desired here.
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
public abstract class IntegrationTestSupport {

    // Unique-per-JVM-run counter so parallel/repeated test methods never collide on slug/email/sku
    // uniqueness constraints, without needing @DirtiesContext or manual per-test prefixes.
    private static final AtomicLong SEQ = new AtomicLong(System.currentTimeMillis() % 1_000_000);

    @Autowired
    protected MockMvc mockMvc;
    protected final ObjectMapper objectMapper = new ObjectMapper();
    @Autowired
    protected StoreRepository storeRepository;
    @Autowired
    protected UserRepository userRepository;
    @Autowired
    protected CustomerRepository customerRepository;
    @Autowired
    protected CategoryRepository categoryRepository;
    @Autowired
    protected ProductRepository productRepository;
    @Autowired
    protected PasswordEncoder passwordEncoder;
    @Autowired
    protected SettingRepository settingRepository;

    protected static final String TEST_PASSWORD = "password123";

    protected static long nextSeq() {
        return SEQ.incrementAndGet();
    }

    /**
     * Bytes of a real 1x1 PNG. Uploads are validated by magic bytes since A8, so a fixture can no
     * longer be an arbitrary string with a .png name — that's exactly what the validator rejects.
     */
    protected static byte[] pngBytes() {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB), "png", out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo generar el PNG de prueba", e);
        }
    }

    protected String hostHeader(Store store) {
        return store.getSlug() + ".localhost";
    }

    protected Store createStore(String slugPrefix) {
        Store store = Store.builder()
                .name(slugPrefix + " Test Store")
                .slug(slugPrefix + "-" + nextSeq())
                .status("active")
                .build();
        return storeRepository.save(store);
    }

    protected User createAdmin(Store store, String emailPrefix) {
        User user = User.builder()
                .store(store)
                .name("Admin " + emailPrefix)
                .email(emailPrefix + "-" + nextSeq() + "@test.local")
                .password(passwordEncoder.encode(TEST_PASSWORD))
                .isActive(true)
                .isAdmin(true)
                .build();
        return userRepository.save(user);
    }

    protected Customer createCustomer(Store store, String emailPrefix) {
        Customer customer = new Customer();
        customer.setStore(store);
        customer.setEmail(emailPrefix + "-" + nextSeq() + "@test.local");
        customer.setPassword(passwordEncoder.encode(TEST_PASSWORD));
        customer.setFirstName("Test");
        customer.setLastName("Customer");
        customer.setAccountStatus(AccountStatus.ACTIVE);
        return customerRepository.save(customer);
    }

    protected Category createCategory(Store store, String namePrefix) {
        long seq = nextSeq();
        Category category = Category.builder()
                .store(store)
                .name(namePrefix + " " + seq)
                .slug("cat-" + seq)
                .active(true)
                .build();
        return categoryRepository.save(category);
    }

    protected Product createProduct(Store store, Category category, String namePrefix, BigDecimal price) {
        long seq = nextSeq();
        Product product = Product.builder()
                .store(store)
                .category(category)
                .name(namePrefix + " " + seq)
                .slug("prod-" + seq)
                .sku("SKU-" + seq)
                .productType(ProductType.SIMPLE)
                .price(price)
                .stock(10)
                .manageStock(true)
                .active(true)
                .isFeatured(true)
                .build();
        return productRepository.save(product);
    }

    /**
     * Marks a store as not shipping at all. Since A7, a checkout is refused with 409 when the store
     * ships but no zone covers the address — which is every store that has no zones configured.
     * Tests that aren't about shipping (order totals, payment gateways, admin order CRUD) use this
     * so they don't each have to seed a zone, a method and a rate. Tests that ARE about shipping
     * seed real ones instead — see ShippingCoverageTest.
     */
    protected void disableShipping(Store store) {
        setSetting(store, "shipping_enabled", "false");
    }

    protected void setSetting(Store store, String key, String value) {
        Setting setting = new Setting();
        setting.setStore(store);
        setting.setSettingKey(key);
        setting.setValue(value);
        settingRepository.save(setting);
    }

    protected String loginAdmin(Store store, User user) throws Exception {
        return login("/api/admin/auth/login", store, user.getEmail());
    }

    protected String loginCustomer(Store store, Customer customer) throws Exception {
        return login("/api/customer/auth/login", store, customer.getEmail());
    }

    private String login(String path, Store store, String email) throws Exception {
        String body = objectMapper.writeValueAsString(new java.util.HashMap<>() {{
            put("email", email);
            put("password", TEST_PASSWORD);
        }});
        MvcResult result = mockMvc.perform(post(path)
                        .header("Host", store.getSlug() + ".localhost")
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        return json.get("token").asText();
    }

    // Every test class using this base runs its own @BeforeEach through JUnit's normal
    // inheritance — this one is intentionally empty, kept as an extension point.
    @BeforeEach
    void baseSetUp() {
        // Confirms the test is actually running inside the expected active transaction —
        // fails loudly (instead of silently leaking fixtures) if that assumption ever breaks.
        if (!TestTransaction.isActive()) {
            throw new IllegalStateException("Expected an active test transaction");
        }
    }
}
