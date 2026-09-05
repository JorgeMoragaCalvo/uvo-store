package org.uvo.uvostore.admin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.uvo.uvostore.entity.catalog.Category;
import org.uvo.uvostore.entity.catalog.Product;
import org.uvo.uvostore.entity.security.User;
import org.uvo.uvostore.entity.tenant.Store;
import org.uvo.uvostore.support.IntegrationTestSupport;

import java.math.BigDecimal;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A1. Roles and permissions existed as entities, as admin CRUD and as JWT authorities, but nothing
 * ever checked them: {@code SecurityConfig} only demanded ROLE_ADMIN for all of /api/admin/**, so
 * any authenticated admin could delete products, create other admins and read the payment gateway
 * configuration regardless of their role.
 */
class AdminPermissionsTest extends IntegrationTestSupport {

    @Test
    @DisplayName("Un admin de solo lectura ve los productos pero no puede borrarlos")
    void readOnlyAdminCanListButNotDelete() throws Exception {
        Store store = createStore("perm-ro");
        User readOnly = createAdminWithPermissions(store, "perm-ro", "products.view");
        String token = loginAdmin(store, readOnly);
        Product product = createProduct(store, createCategory(store, "Cat"), "Producto", BigDecimal.TEN);

        mockMvc.perform(get("/api/admin/products")
                        .header("Host", hostHeader(store))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        // The finding, literally.
        mockMvc.perform(delete("/api/admin/products/" + product.getId())
                        .header("Host", hostHeader(store))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Sin users.manage no se pueden crear otros administradores")
    void withoutUsersManageAnAdminCannotCreateAnotherAdmin() throws Exception {
        Store store = createStore("perm-users");
        User restricted = createAdminWithPermissions(store, "perm-users", "products.view", "users.view");
        String token = loginAdmin(store, restricted);

        mockMvc.perform(get("/api/admin/users")
                        .header("Host", hostHeader(store))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        mockMvc.perform(multipart("/api/admin/users")
                        .header("Host", hostHeader(store))
                        .header("Authorization", "Bearer " + token)
                        .param("name", "Intruso")
                        .param("email", "intruso@test.local")
                        .param("password", "password123")
                        .param("active", "true")
                        .param("sendInvitation", "false"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Sin payments.view no se lee la configuración de las pasarelas")
    void withoutPaymentsViewTheGatewayConfigIsHidden() throws Exception {
        Store store = createStore("perm-pay");
        User restricted = createAdminWithPermissions(store, "perm-pay", "products.view");
        String token = loginAdmin(store, restricted);

        mockMvc.perform(get("/api/admin/payment-gateways")
                        .header("Host", hostHeader(store))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Sin settings.view tampoco se leen los ajustes: los controladores fuera de controller/admin también están cubiertos")
    void controllersOutsideTheAdminPackageAreCoveredToo() throws Exception {
        Store store = createStore("perm-settings");
        User restricted = createAdminWithPermissions(store, "perm-settings", "products.view");
        String token = loginAdmin(store, restricted);

        // These three live in controller/settings/**, which is exactly why they were easy to miss.
        for (String path : new String[]{"/api/admin/settings/general", "/api/admin/settings/store", "/api/admin/home/banners"}) {
            mockMvc.perform(get(path)
                            .header("Host", hostHeader(store))
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isForbidden());
        }
    }

    @Test
    @DisplayName("El rol completo puede con todo: las anotaciones no bloquean de más")
    void aFullAccessAdminIsNotBlocked() throws Exception {
        Store store = createStore("perm-full");
        User admin = createAdmin(store, "perm-full");
        String token = loginAdmin(store, admin);
        Category category = createCategory(store, "Cat");
        Product product = createProduct(store, category, "Producto", BigDecimal.TEN);

        for (String path : new String[]{"/api/admin/products", "/api/admin/categories", "/api/admin/orders",
                "/api/admin/customers", "/api/admin/coupons", "/api/admin/users", "/api/admin/roles",
                "/api/admin/shipping/zones", "/api/admin/payment-gateways", "/api/admin/home/banners",
                "/api/admin/settings/general"}) {
            mockMvc.perform(get(path)
                            .header("Host", hostHeader(store))
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk());
        }

        // Reports take a mandatory date range.
        mockMvc.perform(get("/api/admin/reports/sales/summary")
                        .header("Host", hostHeader(store))
                        .header("Authorization", "Bearer " + token)
                        .param("startDate", "2026-01-01")
                        .param("endDate", "2026-12-31"))
                .andExpect(status().isOk());

        mockMvc.perform(delete("/api/admin/products/" + product.getId())
                        .header("Host", hostHeader(store))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("El login devuelve los permisos, que es lo que el panel necesita para filtrar el menú")
    void loginReturnsTheEffectivePermissions() throws Exception {
        Store store = createStore("perm-login");
        User restricted = createAdminWithPermissions(store, "perm-login", "products.view", "orders.view");

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/admin/auth/login")
                        .header("Host", hostHeader(store))
                        .contentType("application/json")
                        .content("{\"email\":\"" + restricted.getEmail() + "\",\"password\":\"" + TEST_PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.permissions", org.hamcrest.Matchers.containsInAnyOrder("products.view", "orders.view")));
    }
}
