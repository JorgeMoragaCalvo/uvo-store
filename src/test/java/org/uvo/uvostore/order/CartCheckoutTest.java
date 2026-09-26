package org.uvo.uvostore.order;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.uvo.uvostore.entity.catalog.Category;
import org.uvo.uvostore.entity.catalog.Product;
import org.uvo.uvostore.entity.order.Coupon;
import org.uvo.uvostore.entity.order.Order;
import org.uvo.uvostore.entity.order.enums.CouponType;
import org.uvo.uvostore.entity.tenant.Store;
import org.uvo.uvostore.repository.CouponRepository;
import org.uvo.uvostore.repository.OrderRepository;
import org.uvo.uvostore.support.IntegrationTestSupport;

import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Money math and cross-tenant safety on the /api/v1/cart/* and /api/v1/checkout endpoints —
// these are the highest business-risk endpoints (real charges get created off of Order.total),
// so pricing correctness and store isolation are asserted end to end through MockMvc rather than
// unit-testing CartPricingServiceImpl/CheckoutServiceImpl in isolation.
class CartCheckoutTest extends IntegrationTestSupport {

    @Autowired
    private OrderRepository orderRepository;
    @Autowired
    private CouponRepository couponRepository;

    @Test
    void cartCalculateReturnsCorrectTotalsForKnownPriceAndTaxRate() throws Exception {
        Store store = createStore("cart-calc");
        setSetting(store, "tax_rate", "19");
        Category category = createCategory(store, "Cat");
        Product product = createProduct(store, category, "Producto", BigDecimal.valueOf(1000));

        String body = """
                {"items":[{"id":%d,"type":"product","quantity":2}]}
                """.formatted(product.getId());

        mockMvc.perform(post("/api/v1/cart/calculate")
                        .header("Host", hostHeader(store))
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subtotalWithoutTax").value(2000))
                .andExpect(jsonPath("$.taxAmount").value(380))
                .andExpect(jsonPath("$.shippingCost").value(0))
                .andExpect(jsonPath("$.total").value(2380));
    }

    @Test
    void aPriceThatIsNotAMultipleOfOneHundredStillTotalsWholePesos() throws Exception {
        // F06, el caso exacto del hallazgo. 9.990 + 19 % son 11.888,10: el IVA salía con céntimos, el
        // total los heredaba, y como las pasarelas cobran entero (11.888) markPaid rechazaba el
        // importe y la orden se quedaba PENDING. No era un ejemplo desafortunado: subtotal × 0,19 solo
        // da entero si el subtotal es múltiplo de 100, y los precios chilenos acaban en 90 o 990.
        Store store = createStore("cart-calc-frac");
        setSetting(store, "tax_rate", "19");
        disableShipping(store);
        Category category = createCategory(store, "Cat");
        Product product = createProduct(store, category, "Producto", BigDecimal.valueOf(9990));

        // Se comprueba contra la orden guardada, no contra el JSON: jsonPath().value(11888) de Spring
        // reevalúa la ruta con el tipo del valor esperado, así que un 11.888,10 se lee como 11.888 y
        // la aserción pasa igual. Es, de paso, por lo que el test que ya existía aquí (2.380) nunca
        // pudo detectar esto.
        Order order = checkoutAndLoad(store, product);

        assertEquals(0, new BigDecimal("1898").compareTo(order.getTaxAmount()));
        assertEquals(0, new BigDecimal("11888").compareTo(order.getTotal()));
        assertEquals(0, order.getSubtotal().add(order.getTaxAmount()).compareTo(order.getTotal()),
                "el total tiene que ser exactamente la suma de sus partes");
    }

    @Test
    void aPercentageCouponOnTaxInclusivePricesAlsoTotalsWholePesos() throws Exception {
        // La otra vía, y la que muerde incluso con prices_include_tax=true: el 10 % de un subtotal sin
        // IVA que ya era fraccionario daba 4.115,13. Son los números de ORD-NDVV268K, la orden real
        // que quedó atrapada en PENDING — y 44.855 es justo lo que PaymentServiceImplTest y
        // MercadoPagoPreferenceTest afirman que se cobra. Con el arreglo, los tres números coinciden.
        Store store = createStore("cart-calc-coupon");
        setSetting(store, "tax_rate", "19");
        setSetting(store, "prices_include_tax", "true");
        disableShipping(store);
        Category category = createCategory(store, "Cat");
        Product product = createProduct(store, category, "Producto", BigDecimal.valueOf(48970));

        Coupon coupon = new Coupon();
        coupon.setStore(store);
        coupon.setCode("DIEZ-" + nextSeq());
        coupon.setName("10 por ciento");
        coupon.setType(CouponType.PERCENTAGE);
        coupon.setValue(BigDecimal.TEN);
        coupon.setActive(true);
        couponRepository.save(coupon);

        Order order = checkoutAndLoad(store, product, coupon.getCode());

        assertEquals(0, new BigDecimal("4115").compareTo(order.getDiscountAmount()));
        assertEquals(0, new BigDecimal("44855").compareTo(order.getTotal()));
    }

    @Test
    void cartValidateRejectsQuantityExceedingStock() throws Exception {
        Store store = createStore("cart-val-over");
        Category category = createCategory(store, "Cat");
        Product product = createProduct(store, category, "Producto", BigDecimal.TEN); // stock=10

        String body = """
                {"items":[{"id":%d,"type":"product","quantity":11}]}
                """.formatted(product.getId());

        mockMvc.perform(post("/api/v1/cart/validate")
                        .header("Host", hostHeader(store))
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.errors['items.0']").exists());
    }

    @Test
    void cartValidateAcceptsQuantityWithinStock() throws Exception {
        Store store = createStore("cart-val-ok");
        Category category = createCategory(store, "Cat");
        Product product = createProduct(store, category, "Producto", BigDecimal.TEN); // stock=10

        String body = """
                {"items":[{"id":%d,"type":"product","quantity":5}]}
                """.formatted(product.getId());

        mockMvc.perform(post("/api/v1/cart/validate")
                        .header("Host", hostHeader(store))
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.items[0].maxQuantity").value(10));
    }

    @Test
    void checkoutCreatesOrderWithCorrectTotalsAndStatus() throws Exception {
        Store store = createStore("checkout-ok");
        setSetting(store, "tax_rate", "19");
        // Totals and status are what matters here; shipping has its own test.
        disableShipping(store);
        Category category = createCategory(store, "Cat");
        Product product = createProduct(store, category, "Producto", BigDecimal.valueOf(1000));

        String body = checkoutBody(product.getId(), 2, "manual");

        String response = mockMvc.perform(post("/api/v1/checkout")
                        .header("Host", hostHeader(store))
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderId").exists())
                .andExpect(jsonPath("$.total").value(2380))
                .andReturn().getResponse().getContentAsString();

        long orderId = objectMapper.readTree(response).get("orderId").asLong();
        Optional<Order> saved = orderRepository.findById(orderId);
        assertTrue(saved.isPresent());
        Order order = saved.get();
        assertEquals(store.getId(), order.getStore().getId());
        assertEquals("PENDING", order.getStatus().name());
        assertEquals("MANUAL", order.getPaymentMethod().name());
        assertEquals(0, BigDecimal.valueOf(2380).compareTo(order.getTotal()));
    }

    @Test
    void checkoutRejectsProductBelongingToAnotherStore() throws Exception {
        Store storeA = createStore("checkout-cross-a");
        Store storeB = createStore("checkout-cross-b");
        Category categoryB = createCategory(storeB, "Cat B");
        Product productB = createProduct(storeB, categoryB, "Producto B", BigDecimal.valueOf(1000));

        String body = checkoutBody(productB.getId(), 1, "manual");

        mockMvc.perform(post("/api/v1/checkout")
                        .header("Host", hostHeader(storeA))
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isNotFound());
    }

    @Test
    void checkoutRejectsEmptyCart() throws Exception {
        Store store = createStore("checkout-empty");

        String body = """
                {
                  "customer": {"email":"a@test.local","firstName":"A","lastName":"B","phone":"+56911111111"},
                  "shippingAddress": {"addressLine1":"Calle 1","city":"Santiago","state":"RM","postalCode":"8320000","country":"CL"},
                  "region": "RM",
                  "commune": "Santiago",
                  "items": [],
                  "paymentMethod": "manual"
                }
                """;

        mockMvc.perform(post("/api/v1/checkout")
                        .header("Host", hostHeader(store))
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void cartValidateAddsUpDuplicateLinesOfTheSameProduct() throws Exception {
        // F10. Cada línea se comparaba por su cuenta contra el stock completo, así que seis y seis
        // pasaban las dos con stock 10. La orden se cobraba y al descontar una de las dos líneas no
        // cabía — con el pago ya hecho. Se mide el total pedido por SKU.
        Store store = createStore("cart-val-dup");
        Category category = createCategory(store, "Cat");
        Product product = createProduct(store, category, "Producto", BigDecimal.TEN); // stock=10

        String body = """
                {"items":[{"id":%d,"type":"product","quantity":6},{"id":%d,"type":"product","quantity":6}]}
                """.formatted(product.getId(), product.getId());

        mockMvc.perform(post("/api/v1/cart/validate")
                        .header("Host", hostHeader(store))
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(false));
    }

    @Test
    void cartValidateStillAcceptsDuplicateLinesThatFitTogether() throws Exception {
        // El control positivo: repetir un producto no es un error por sí mismo, solo pasarse del stock
        // entre todas sus líneas.
        Store store = createStore("cart-val-dup-ok");
        Category category = createCategory(store, "Cat");
        Product product = createProduct(store, category, "Producto", BigDecimal.TEN); // stock=10

        String body = """
                {"items":[{"id":%d,"type":"product","quantity":4},{"id":%d,"type":"product","quantity":4}]}
                """.formatted(product.getId(), product.getId());

        mockMvc.perform(post("/api/v1/cart/validate")
                        .header("Host", hostHeader(store))
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true));
    }

    @Test
    void checkoutRejectsDuplicateLinesThatExceedStockTogether() throws Exception {
        // El mismo caso llegando al checkout, que reutiliza esta validación: 409, no una orden cobrada
        // que luego no se puede servir.
        Store store = createStore("checkout-dup");
        disableShipping(store);
        Category category = createCategory(store, "Cat");
        Product product = createProduct(store, category, "Producto", BigDecimal.valueOf(1000)); // stock=10

        String body = """
                {
                  "customer": {"email":"a@test.local","firstName":"A","lastName":"B","phone":"+56911111111"},
                  "shippingAddress": {"addressLine1":"Calle 1","city":"Santiago","state":"RM","postalCode":"8320000","country":"CL"},
                  "region": "RM",
                  "commune": "Santiago",
                  "items": [{"id":%d,"type":"product","quantity":6},{"id":%d,"type":"product","quantity":6}],
                  "paymentMethod": "manual"
                }
                """.formatted(product.getId(), product.getId());

        mockMvc.perform(post("/api/v1/checkout")
                        .header("Host", hostHeader(store))
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isConflict());
    }

    private Order checkoutAndLoad(Store store, Product product) throws Exception {
        return checkoutAndLoad(store, product, null);
    }

    /** Hace un checkout real y devuelve la orden guardada, que es donde se ve si hay céntimos. */
    private Order checkoutAndLoad(Store store, Product product, String couponCode) throws Exception {
        String coupon = couponCode == null ? "" : "\"couponCode\": \"%s\",".formatted(couponCode);
        String body = """
                {
                  "customer": {"email":"a@test.local","firstName":"A","lastName":"B","phone":"+56911111111"},
                  "shippingAddress": {"addressLine1":"Calle 1","city":"Santiago","state":"RM","postalCode":"8320000","country":"CL"},
                  "region": "RM",
                  "commune": "Santiago",
                  "items": [{"id":%d,"type":"product","quantity":1}],
                  %s
                  "paymentMethod": "manual"
                }
                """.formatted(product.getId(), coupon);

        String response = mockMvc.perform(post("/api/v1/checkout")
                        .header("Host", hostHeader(store))
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return orderRepository.findById(objectMapper.readTree(response).get("orderId").asLong()).orElseThrow();
    }

    private String checkoutBody(Long productId, int quantity, String paymentMethod) {
        return """
                {
                  "customer": {"email":"a@test.local","firstName":"A","lastName":"B","phone":"+56911111111"},
                  "shippingAddress": {"addressLine1":"Calle 1","city":"Santiago","state":"RM","postalCode":"8320000","country":"CL"},
                  "region": "RM",
                  "commune": "Santiago",
                  "items": [{"id":%d,"type":"product","quantity":%d}],
                  "paymentMethod": "%s"
                }
                """.formatted(productId, quantity, paymentMethod);
    }
}
