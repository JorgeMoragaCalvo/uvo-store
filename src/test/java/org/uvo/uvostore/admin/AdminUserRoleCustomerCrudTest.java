package org.uvo.uvostore.admin;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.uvo.uvostore.entity.customer.Customer;
import org.uvo.uvostore.entity.security.Permission;
import org.uvo.uvostore.entity.security.User;
import org.uvo.uvostore.entity.tenant.Store;
import org.uvo.uvostore.repository.PermissionRepository;
import org.uvo.uvostore.support.IntegrationTestSupport;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// CRUD + cross-tenant coverage for /api/admin/users, /api/admin/roles, and read/delete coverage
// for /api/admin/customers. Permissions are a global (non-store-scoped) capability catalog per
// V8__stores.sql's comment, so role tests reuse whatever rows already exist in the dev database
// rather than seeding new ones.
class AdminUserRoleCustomerCrudTest extends IntegrationTestSupport {

    @Autowired
    private PermissionRepository permissionRepository;

    @Test
    void userCanBeCreatedUpdatedToggledAndDeleted() throws Exception {
        Store store = createStore("user-crud");
        User admin = createAdmin(store, "user-crud");
        String token = loginAdmin(store, admin);

        String createResponse = mockMvc.perform(multipart("/api/admin/users")
                        .header("Host", hostHeader(store))
                        .header("Authorization", "Bearer " + token)
                        .param("name", "Nuevo Vendedor")
                        .param("email", "vendedor-" + nextSeq() + "@test.local")
                        .param("password", "password123")
                        .param("active", "true")
                        .param("sendInvitation", "false"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Nuevo Vendedor"))
                .andExpect(jsonPath("$.active").value(true))
                .andReturn().getResponse().getContentAsString();
        long userId = objectMapper.readTree(createResponse).get("id").asLong();

        var updateRequest = multipart("/api/admin/users/" + userId);
        updateRequest.with(req -> {
            req.setMethod("PUT");
            return req;
        });
        mockMvc.perform(updateRequest
                        .header("Host", hostHeader(store))
                        .header("Authorization", "Bearer " + token)
                        .param("name", "Vendedor Actualizado")
                        .param("email", "vendedor-" + nextSeq() + "@test.local")
                        .param("active", "true")
                        .param("sendInvitation", "false"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Vendedor Actualizado"));

        mockMvc.perform(post("/api/admin/users/" + userId + "/toggle-status")
                        .header("Host", hostHeader(store))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));

        mockMvc.perform(delete("/api/admin/users/" + userId)
                        .header("Host", hostHeader(store))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/admin/users/" + userId)
                        .header("Host", hostHeader(store))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }

    @Test
    void userCannotDeleteOrDeactivateThemselves() throws Exception {
        Store store = createStore("user-self");
        User admin = createAdmin(store, "user-self");
        String token = loginAdmin(store, admin);

        mockMvc.perform(delete("/api/admin/users/" + admin.getId())
                        .header("Host", hostHeader(store))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/admin/users/" + admin.getId() + "/toggle-status")
                        .header("Host", hostHeader(store))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    @Test
    void userEndpointsRejectAnotherStoresUser() throws Exception {
        Store storeA = createStore("user-cross-a");
        Store storeB = createStore("user-cross-b");
        User adminA = createAdmin(storeA, "user-cross-a");
        User userB = createAdmin(storeB, "user-cross-b");
        String tokenA = loginAdmin(storeA, adminA);

        mockMvc.perform(get("/api/admin/users/" + userB.getId())
                        .header("Host", hostHeader(storeA))
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isNotFound());

        mockMvc.perform(delete("/api/admin/users/" + userB.getId())
                        .header("Host", hostHeader(storeA))
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isNotFound());
    }

    @Test
    void roleCanBeCreatedUpdatedAndDeleted() throws Exception {
        Store store = createStore("role-crud");
        User admin = createAdmin(store, "role-crud");
        String token = loginAdmin(store, admin);
        Long permissionId = anyPermissionId();

        String createBody = "{\"name\":\"Vendedor\",\"permissionIds\":[" + permissionId + "]}";
        String createResponse = mockMvc.perform(post("/api/admin/roles")
                        .header("Host", hostHeader(store))
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content(createBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Vendedor"))
                .andExpect(jsonPath("$.permissions").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        long roleId = objectMapper.readTree(createResponse).get("id").asLong();

        String updateBody = "{\"name\":\"Vendedor Senior\",\"permissionIds\":[" + permissionId + "]}";
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/admin/roles/" + roleId)
                        .header("Host", hostHeader(store))
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content(updateBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Vendedor Senior"));

        mockMvc.perform(delete("/api/admin/roles/" + roleId)
                        .header("Host", hostHeader(store))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/admin/roles/" + roleId)
                        .header("Host", hostHeader(store))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }

    @Test
    void roleEndpointsRejectAnotherStoresRole() throws Exception {
        Store storeA = createStore("role-cross-a");
        Store storeB = createStore("role-cross-b");
        User adminA = createAdmin(storeA, "role-cross-a");
        User adminB = createAdmin(storeB, "role-cross-b");
        String tokenA = loginAdmin(storeA, adminA);
        String tokenB = loginAdmin(storeB, adminB);
        Long permissionId = anyPermissionId();

        String createResponse = mockMvc.perform(post("/api/admin/roles")
                        .header("Host", hostHeader(storeB))
                        .header("Authorization", "Bearer " + tokenB)
                        .contentType("application/json")
                        .content("{\"name\":\"Solo B\",\"permissionIds\":[" + permissionId + "]}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        long roleIdB = objectMapper.readTree(createResponse).get("id").asLong();

        mockMvc.perform(get("/api/admin/roles/" + roleIdB)
                        .header("Host", hostHeader(storeA))
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isNotFound());
    }

    @Test
    void customerCanBeReadThenDeletedByAdmin() throws Exception {
        Store store = createStore("customer-crud");
        User admin = createAdmin(store, "customer-crud");
        Customer customer = createCustomer(store, "customer-crud");
        String token = loginAdmin(store, admin);

        mockMvc.perform(get("/api/admin/customers/" + customer.getId())
                        .header("Host", hostHeader(store))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(customer.getEmail()));

        mockMvc.perform(delete("/api/admin/customers/" + customer.getId())
                        .header("Host", hostHeader(store))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/admin/customers/" + customer.getId())
                        .header("Host", hostHeader(store))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }

    @Test
    void customerEndpointsRejectAnotherStoresCustomer() throws Exception {
        Store storeA = createStore("customer-cross-a");
        Store storeB = createStore("customer-cross-b");
        User adminA = createAdmin(storeA, "customer-cross-a");
        Customer customerB = createCustomer(storeB, "customer-cross-b");
        String tokenA = loginAdmin(storeA, adminA);

        mockMvc.perform(get("/api/admin/customers/" + customerB.getId())
                        .header("Host", hostHeader(storeA))
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isNotFound());

        mockMvc.perform(delete("/api/admin/customers/" + customerB.getId())
                        .header("Host", hostHeader(storeA))
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isNotFound());
    }

    // Permissions are a global capability catalog seeded outside of Flyway (by an admin-panel
    // action or manual data load), so a fresh test database has none — create one on the fly
    // instead of assuming seed data exists.
    private Long anyPermissionId() {
        Permission permission = new Permission();
        permission.setName("test.permission." + nextSeq());
        permission.setGuardName("web");
        return permissionRepository.save(permission).getId();
    }
}
