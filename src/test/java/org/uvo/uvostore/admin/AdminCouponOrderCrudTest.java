package org.uvo.uvostore.admin;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.uvo.uvostore.entity.catalog.Category;
import org.uvo.uvostore.entity.catalog.Product;
import org.uvo.uvostore.entity.security.User;
import org.uvo.uvostore.entity.tenant.Store;
import org.uvo.uvostore.repository.OrderRepository;
import org.uvo.uvostore.support.IntegrationTestSupport;

import java.math.BigDecimal;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// CRUD + cross-tenant coverage for /api/admin/coupons and status-transition coverage for
// /api/admin/orders — orders are created through the real checkout flow (already covered in
// CartCheckoutTest) rather than inserted directly, so these tests only exercise the admin
// status/tracking mutations on top of a known-good order.
class AdminCouponOrderCrudTest extends IntegrationTestSupport {

    @Autowired
    private OrderRepository orderRepository;

    @Test
    void couponCanBeCreatedUpdatedToggledAndDeleted() throws Exception {
        Store store = createStore("coupon-crud");
        User admin = createAdmin(store, "coupon-crud");
        String token = loginAdmin(store, admin);

        String body = """
                {"code":"DESC10","name":"10%% off","type":"percentage","value":10,"active":true}
                """.replace("%%", "%");

        String createResponse = mockMvc.perform(post("/api/admin/coupons")
                        .header("Host", hostHeader(store))
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("DESC10"))
                .andExpect(jsonPath("$.active").value(true))
                .andReturn().getResponse().getContentAsString();
        long couponId = objectMapper.readTree(createResponse).get("id").asLong();

        String updateBody = """
                {"code":"DESC15","name":"15%% off","type":"percentage","value":15,"active":true}
                """.replace("%%", "%");
        mockMvc.perform(put("/api/admin/coupons/" + couponId)
                        .header("Host", hostHeader(store))
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content(updateBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("DESC15"))
                .andExpect(jsonPath("$.value").value(15));

        mockMvc.perform(post("/api/admin/coupons/" + couponId + "/toggle-status")
                        .header("Host", hostHeader(store))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));

        mockMvc.perform(delete("/api/admin/coupons/" + couponId)
                        .header("Host", hostHeader(store))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/admin/coupons/" + couponId)
                        .header("Host", hostHeader(store))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }

    @Test
    void couponEndpointsRejectAnotherStoresCoupon() throws Exception {
        Store storeA = createStore("coupon-cross-a");
        Store storeB = createStore("coupon-cross-b");
        User adminA = createAdmin(storeA, "coupon-cross-a");
        User adminB = createAdmin(storeB, "coupon-cross-b");
        String tokenA = loginAdmin(storeA, adminA);
        String tokenB = loginAdmin(storeB, adminB);

        String createResponse = mockMvc.perform(post("/api/admin/coupons")
                        .header("Host", hostHeader(storeB))
                        .header("Authorization", "Bearer " + tokenB)
                        .contentType("application/json")
                        .content("{\"code\":\"ONLYB\",\"name\":\"Solo B\",\"type\":\"fixed\",\"value\":500,\"active\":true}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        long couponIdB = objectMapper.readTree(createResponse).get("id").asLong();

        mockMvc.perform(get("/api/admin/coupons/" + couponIdB)
                        .header("Host", hostHeader(storeA))
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isNotFound());

        mockMvc.perform(delete("/api/admin/coupons/" + couponIdB)
                        .header("Host", hostHeader(storeA))
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isNotFound());
    }

    @Test
    void orderStatusAndTrackingCanBeUpdatedByAdmin() throws Exception {
        Store store = createStore("order-status");
        User admin = createAdmin(store, "order-status");
        String token = loginAdmin(store, admin);
        long orderId = createPendingOrder(store);

        mockMvc.perform(post("/api/admin/orders/" + orderId + "/mark-processing")
                        .header("Host", hostHeader(store))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PROCESSING"));

        mockMvc.perform(post("/api/admin/orders/" + orderId + "/tracking")
                        .header("Host", hostHeader(store))
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content("{\"trackingNumber\":\"TRACK-123\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SHIPPED"))
                .andExpect(jsonPath("$.trackingNumber").value("TRACK-123"));

        mockMvc.perform(post("/api/admin/orders/" + orderId + "/mark-delivered")
                        .header("Host", hostHeader(store))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DELIVERED"));

        assertOrderStatusPersisted(orderId, "DELIVERED");
    }

    @Test
    void orderEndpointsRejectAnotherStoresOrder() throws Exception {
        Store storeA = createStore("order-cross-a");
        Store storeB = createStore("order-cross-b");
        User adminA = createAdmin(storeA, "order-cross-a");
        String tokenA = loginAdmin(storeA, adminA);
        long orderIdB = createPendingOrder(storeB);

        mockMvc.perform(get("/api/admin/orders/" + orderIdB)
                        .header("Host", hostHeader(storeA))
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isNotFound());

        mockMvc.perform(post("/api/admin/orders/" + orderIdB + "/mark-processing")
                        .header("Host", hostHeader(storeA))
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isNotFound());
    }

    private void assertOrderStatusPersisted(long orderId, String expectedStatus) {
        var order = orderRepository.findById(orderId).orElseThrow();
        org.junit.jupiter.api.Assertions.assertEquals(expectedStatus, order.getStatus().name());
    }

    private long createPendingOrder(Store store) throws Exception {
        Category category = createCategory(store, "Cat");
        Product product = createProduct(store, category, "Producto", BigDecimal.valueOf(1000));

        String body = """
                {
                  "customer": {"email":"a@test.local","firstName":"A","lastName":"B","phone":"+56911111111"},
                  "shippingAddress": {"addressLine1":"Calle 1","city":"Santiago","state":"RM","postalCode":"8320000","country":"CL"},
                  "region": "RM",
                  "commune": "Santiago",
                  "items": [{"id":%d,"type":"product","quantity":1}],
                  "paymentMethod": "manual"
                }
                """.formatted(product.getId());

        String response = mockMvc.perform(post("/api/v1/checkout")
                        .header("Host", hostHeader(store))
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        return objectMapper.readTree(response).get("orderId").asLong();
    }
}
