package org.uvo.uvostore.multitenancy;

import org.junit.jupiter.api.Test;
import org.uvo.uvostore.entity.catalog.Category;
import org.uvo.uvostore.entity.catalog.Product;
import org.uvo.uvostore.entity.customer.Customer;
import org.uvo.uvostore.entity.security.User;
import org.uvo.uvostore.entity.tenant.Store;
import org.uvo.uvostore.support.IntegrationTestSupport;

import java.math.BigDecimal;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Fase 0's core guarantee, automated: a store must never be able to read or write another
// store's data, whether through the public storefront API, the admin API, or a replayed JWT.
// Everything here was previously verified by hand with curl during the Fase 0/1 sessions — this
// class is that verification turned into a regression test.
class MultiTenancyIsolationTest extends IntegrationTestSupport {

    @Test
    void publicProductsEndpointOnlyReturnsTheResolvedStoresProducts() throws Exception {
        Store storeA = createStore("tenant-a");
        Store storeB = createStore("tenant-b");
        Category categoryA = createCategory(storeA, "Ropa A");
        Category categoryB = createCategory(storeB, "Ropa B");
        Product productA = createProduct(storeA, categoryA, "Producto Exclusivo A", BigDecimal.valueOf(1000));
        Product productB = createProduct(storeB, categoryB, "Producto Exclusivo B", BigDecimal.valueOf(2000));

        mockMvc.perform(get("/api/v1/products").header("Host", hostHeader(storeA)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].name").value(org.hamcrest.Matchers.hasItem(productA.getName())))
                .andExpect(jsonPath("$.content[*].name").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItem(productB.getName()))));

        mockMvc.perform(get("/api/v1/products").header("Host", hostHeader(storeB)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].name").value(org.hamcrest.Matchers.hasItem(productB.getName())))
                .andExpect(jsonPath("$.content[*].name").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItem(productA.getName()))));
    }

    @Test
    void requestsWithNoResolvableSubdomainAreRejected() throws Exception {
        Store store = createStore("tenant-nohost");
        User admin = createAdmin(store, "admin-nohost");

        String body = "{\"email\":\"" + admin.getEmail() + "\",\"password\":\"" + TEST_PASSWORD + "\"}";

        // Bare "localhost" (no subdomain) — TenantResolutionFilter resolves nothing.
        mockMvc.perform(post("/api/admin/auth/login")
                        .header("Host", "localhost")
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isBadRequest());

        // A subdomain that doesn't match any store's slug.
        mockMvc.perform(post("/api/admin/auth/login")
                        .header("Host", "no-such-store.localhost")
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void adminJwtIsRejectedWhenReplayedAgainstAnotherStoresSubdomain() throws Exception {
        Store storeA = createStore("tenant-jwt-a");
        Store storeB = createStore("tenant-jwt-b");
        User adminA = createAdmin(storeA, "admin-jwt-a");
        createAdmin(storeB, "admin-jwt-b"); // just to prove storeB is a real, distinct tenant

        String tokenA = loginAdmin(storeA, adminA);

        // Works fine against its own store.
        mockMvc.perform(get("/api/admin/products")
                        .header("Host", hostHeader(storeA))
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk());

        // Same token, different store's subdomain — must be rejected (sid-vs-Host cross-check
        // in JwtAuthenticationFilter), not silently authorize against the wrong tenant.
        mockMvc.perform(get("/api/admin/products")
                        .header("Host", hostHeader(storeB))
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminProductsEndpointNeverLeaksAnotherStoresProducts() throws Exception {
        Store storeA = createStore("tenant-admin-a");
        Store storeB = createStore("tenant-admin-b");
        User adminA = createAdmin(storeA, "admin-products-a");
        Category categoryA = createCategory(storeA, "Cat A");
        Category categoryB = createCategory(storeB, "Cat B");
        Product productA = createProduct(storeA, categoryA, "Admin Visible A", BigDecimal.TEN);
        Product productB = createProduct(storeB, categoryB, "Admin Hidden B", BigDecimal.TEN);

        String tokenA = loginAdmin(storeA, adminA);

        mockMvc.perform(get("/api/admin/products")
                        .header("Host", hostHeader(storeA))
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].name").value(org.hamcrest.Matchers.hasItem(productA.getName())))
                .andExpect(jsonPath("$.content[*].name").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItem(productB.getName()))));

        // Fetching store B's product by id, authenticated as store A's admin, must 404 —
        // not leak that the row exists, not return its data.
        mockMvc.perform(get("/api/admin/products/" + productB.getId())
                        .header("Host", hostHeader(storeA))
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isNotFound());
    }

    @Test
    void customerEmailIsUniquePerStoreNotGlobally() throws Exception {
        Store storeA = createStore("tenant-email-a");
        Store storeB = createStore("tenant-email-b");
        String sharedEmail = "shared-" + nextSeq() + "@test.local";

        String registerBody = "{\"email\":\"" + sharedEmail + "\",\"password\":\"" + TEST_PASSWORD
                + "\",\"firstName\":\"A\",\"lastName\":\"B\",\"phone\":\"+56911111111\"}";

        // Same email, two different stores — both registrations must succeed independently.
        mockMvc.perform(post("/api/customer/auth/register")
                        .header("Host", hostHeader(storeA))
                        .contentType("application/json")
                        .content(registerBody))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/customer/auth/register")
                        .header("Host", hostHeader(storeB))
                        .contentType("application/json")
                        .content(registerBody))
                .andExpect(status().isOk());

        // But re-registering the SAME email again within store A must be rejected.
        mockMvc.perform(post("/api/customer/auth/register")
                        .header("Host", hostHeader(storeA))
                        .contentType("application/json")
                        .content(registerBody))
                .andExpect(status().isBadRequest());
    }

    @Test
    void customerJwtIsRejectedWhenReplayedAgainstAnotherStoresSubdomain() throws Exception {
        Store storeA = createStore("tenant-cust-jwt-a");
        Store storeB = createStore("tenant-cust-jwt-b");
        Customer customerA = createCustomer(storeA, "cust-jwt-a");

        String tokenA = loginCustomer(storeA, customerA);

        mockMvc.perform(get("/api/customer/account")
                        .header("Host", hostHeader(storeA))
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/customer/account")
                        .header("Host", hostHeader(storeB))
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isForbidden());
    }
}
