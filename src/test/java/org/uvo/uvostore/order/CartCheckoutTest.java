package org.uvo.uvostore.order;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.uvo.uvostore.entity.catalog.Category;
import org.uvo.uvostore.entity.catalog.Product;
import org.uvo.uvostore.entity.order.Order;
import org.uvo.uvostore.entity.tenant.Store;
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
