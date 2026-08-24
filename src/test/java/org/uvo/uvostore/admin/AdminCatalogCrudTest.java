package org.uvo.uvostore.admin;

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

// CRUD + cross-tenant coverage for /api/admin/categories and /api/admin/products. Both controllers
// take multipart/form-data (ModelAttribute-bound file uploads), so requests are built with
// MockMvcRequestBuilders.multipart(...).param(...) rather than a JSON body; PUT is issued as a
// multipart request with its method overridden, which Spring's multipart builder supports directly.
class AdminCatalogCrudTest extends IntegrationTestSupport {

    @Test
    void categoryCanBeCreatedUpdatedAndDeleted() throws Exception {
        Store store = createStore("cat-crud");
        User admin = createAdmin(store, "cat-crud");
        String token = loginAdmin(store, admin);

        String createResponse = mockMvc.perform(multipart("/api/admin/categories")
                        .header("Host", hostHeader(store))
                        .header("Authorization", "Bearer " + token)
                        .param("name", "Ropa")
                        .param("active", "true")
                        .param("sortOrder", "1")
                        .param("isFeatured", "false"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Ropa"))
                .andExpect(jsonPath("$.active").value(true))
                .andReturn().getResponse().getContentAsString();
        long categoryId = objectMapper.readTree(createResponse).get("id").asLong();

        var updateRequest = multipart("/api/admin/categories/" + categoryId);
        updateRequest.with(req -> {
            req.setMethod("PUT");
            return req;
        });
        mockMvc.perform(updateRequest
                        .header("Host", hostHeader(store))
                        .header("Authorization", "Bearer " + token)
                        .param("name", "Ropa Actualizada")
                        .param("active", "false")
                        .param("sortOrder", "2")
                        .param("isFeatured", "false"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Ropa Actualizada"))
                .andExpect(jsonPath("$.active").value(false));

        mockMvc.perform(delete("/api/admin/categories/" + categoryId)
                        .header("Host", hostHeader(store))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/admin/categories/" + categoryId)
                        .header("Host", hostHeader(store))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }

    @Test
    void categoryEndpointsRejectAnotherStoresCategory() throws Exception {
        Store storeA = createStore("cat-cross-a");
        Store storeB = createStore("cat-cross-b");
        User adminA = createAdmin(storeA, "cat-cross-a");
        Category categoryB = createCategory(storeB, "Cat B");
        String tokenA = loginAdmin(storeA, adminA);

        mockMvc.perform(get("/api/admin/categories/" + categoryB.getId())
                        .header("Host", hostHeader(storeA))
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isNotFound());

        mockMvc.perform(delete("/api/admin/categories/" + categoryB.getId())
                        .header("Host", hostHeader(storeA))
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isNotFound());
    }

    @Test
    void simpleProductCanBeCreatedUpdatedToggledAndDeleted() throws Exception {
        Store store = createStore("prod-crud");
        User admin = createAdmin(store, "prod-crud");
        Category category = createCategory(store, "Cat");
        String token = loginAdmin(store, admin);

        String createResponse = mockMvc.perform(multipart("/api/admin/products")
                        .header("Host", hostHeader(store))
                        .header("Authorization", "Bearer " + token)
                        .param("productType", "simple")
                        .param("name", "Zapatillas")
                        .param("categoryId", String.valueOf(category.getId()))
                        .param("active", "true")
                        .param("isFeatured", "false")
                        .param("isNew", "false")
                        .param("sortOrder", "0")
                        .param("isOnSale", "false")
                        .param("sku", "ZAP-001")
                        .param("price", "19990")
                        .param("stock", "5")
                        .param("manageStock", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Zapatillas"))
                .andExpect(jsonPath("$.active").value(true))
                .andReturn().getResponse().getContentAsString();
        long productId = objectMapper.readTree(createResponse).get("id").asLong();

        var updateRequest = multipart("/api/admin/products/" + productId);
        updateRequest.with(req -> {
            req.setMethod("PUT");
            return req;
        });
        mockMvc.perform(updateRequest
                        .header("Host", hostHeader(store))
                        .header("Authorization", "Bearer " + token)
                        .param("productType", "simple")
                        .param("name", "Zapatillas Pro")
                        .param("categoryId", String.valueOf(category.getId()))
                        .param("active", "true")
                        .param("isFeatured", "false")
                        .param("isNew", "false")
                        .param("sortOrder", "0")
                        .param("isOnSale", "false")
                        .param("sku", "ZAP-001")
                        .param("price", "24990")
                        .param("stock", "5")
                        .param("manageStock", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Zapatillas Pro"))
                .andExpect(jsonPath("$.price").value(24990));

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/admin/products/" + productId + "/toggle-active")
                        .header("Host", hostHeader(store))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));

        mockMvc.perform(delete("/api/admin/products/" + productId)
                        .header("Host", hostHeader(store))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/admin/products/" + productId)
                        .header("Host", hostHeader(store))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }

    @Test
    void productEndpointsRejectAnotherStoresProduct() throws Exception {
        Store storeA = createStore("prod-cross-a");
        Store storeB = createStore("prod-cross-b");
        User adminA = createAdmin(storeA, "prod-cross-a");
        Category categoryB = createCategory(storeB, "Cat B");
        Product productB = createProduct(storeB, categoryB, "Producto B", BigDecimal.TEN);
        String tokenA = loginAdmin(storeA, adminA);

        mockMvc.perform(get("/api/admin/products/" + productB.getId())
                        .header("Host", hostHeader(storeA))
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isNotFound());

        mockMvc.perform(delete("/api/admin/products/" + productB.getId())
                        .header("Host", hostHeader(storeA))
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isNotFound());
    }
}
