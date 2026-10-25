package org.uvo.uvostore.audit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import org.uvo.uvostore.config.RequestIdFilter;
import org.uvo.uvostore.entity.security.Permission;
import org.uvo.uvostore.entity.security.Role;
import org.uvo.uvostore.entity.security.User;
import org.uvo.uvostore.entity.tenant.Store;
import org.uvo.uvostore.support.AuditRows;
import org.uvo.uvostore.support.IntegrationTestSupport;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** PROD-18. Altas, cambios y bajas de administradores y de roles, a nombre de quien las hace y sin secretos. */
class UserRoleAuditTest extends IntegrationTestSupport {

    private Store store;
    private User admin;
    private String token;

    @BeforeEach
    void setUp() throws Exception {
        store = createStore("audit-users");
        admin = createAdmin(store, "audit-users");
        token = loginAdmin(store, admin);
    }

    @Test
    @DisplayName("Crear un admin deja quién lo creó, el correo y los roles; nunca la contraseña")
    void creatingAUserRecordsWhoAndWhatButNotThePassword() throws Exception {
        Role role = role("Ventas");
        String email = "vendedor-" + nextSeq() + "@test.local";

        MvcResult result = mockMvc.perform(withAuth(multipart("/api/admin/users"))
                        .param("name", "Vendedor")
                        .param("email", email)
                        .param("password", "ClaveDelVendedor-1")
                        .param("phone", "+56 9 1234 5678")
                        .param("roleId", String.valueOf(role.getId()))
                        .param("active", "true")
                        .param("sendInvitation", "false"))
                .andExpect(status().isOk())
                .andReturn();

        AuditRows.Row row = auditRow(store, "USER_CREATE");
        assertThat(row.actorType()).isEqualTo("ADMIN");
        assertThat(row.actorId()).isEqualTo(admin.getId());
        assertThat(row.actorLabel()).isEqualTo(admin.getEmail());
        assertThat(row.requestId()).isEqualTo(result.getResponse().getHeader(RequestIdFilter.HEADER));
        assertThat(row.json().path("email").asText()).isEqualTo(email);
        assertThat(row.json().path("invited").asBoolean()).isFalse();
        assertThat(row.json().path("roles").get(0).asText()).isEqualTo("Ventas");
        assertThat(row.details()).doesNotContain("ClaveDelVendedor", "1234 5678");
    }

    @Test
    @DisplayName("Editar deja solo lo que cambió: roles que entran y salen, activo, contraseña puesta y campos libres por nombre")
    void updatingAUserRecordsOnlyWhatChanged() throws Exception {
        Role ventas = role("Ventas");
        Role bodega = role("Bodega");
        User user = createAdmin(store, "editado");
        user.setRoles(new HashSet<>(Set.of(ventas)));
        user.setNotes("nota vieja");
        userRepository.save(user);

        mockMvc.perform(update(user, bodega, false)
                        .param("password", "OtraClaveNueva-2")
                        .param("notes", "nota nueva privada"))
                .andExpect(status().isOk());

        AuditRows.Row row = auditRow(store, "USER_UPDATE");
        assertThat(row.targetId()).isEqualTo(user.getId());
        assertThat(row.change("roles").path("added").get(0).asText()).isEqualTo("Bodega");
        assertThat(row.change("roles").path("removed").get(0).asText()).isEqualTo("Ventas");
        assertThat(row.change("active").path("to").asBoolean()).isFalse();
        assertThat(row.change("password").asText()).isEqualTo("replaced");
        assertThat(row.json().path("changedFields").toString()).contains("notes");
        assertThat(row.json().path("sessionsRevoked").asBoolean()).isTrue();
        assertThat(row.details()).doesNotContain("OtraClaveNueva", "nota nueva", "nota vieja");
    }

    @Test
    @DisplayName("Guardar el formulario sin cambiar nada no deja fila")
    void savingWithoutChangesLeavesNoRow() throws Exception {
        Role ventas = role("Ventas");
        User user = createAdmin(store, "sin-cambios");
        user.setRoles(new HashSet<>(Set.of(ventas)));
        userRepository.save(user);

        mockMvc.perform(update(user, ventas, true)).andExpect(status().isOk());

        assertThat(auditRows(store, "USER_UPDATE")).isEmpty();
    }

    @Test
    @DisplayName("Desactivar, reenviar la invitación y borrar dejan su fila, con el correo para saber de quién se trata")
    void toggleResendAndDeleteAreRecorded() throws Exception {
        User user = createAdmin(store, "baja");
        User invited = createAdmin(store, "reenvio");
        invited.setPassword(null);
        invited.setInvitationToken("token-" + nextSeq());
        invited.setInvitationSentAt(Instant.now().minus(2, ChronoUnit.DAYS));
        userRepository.save(invited);

        mockMvc.perform(withAuth(post("/api/admin/users/" + user.getId() + "/toggle-status"))).andExpect(status().isOk());
        mockMvc.perform(withAuth(post("/api/admin/users/" + invited.getId() + "/resend-invitation"))).andExpect(status().isOk());
        mockMvc.perform(withAuth(delete("/api/admin/users/" + user.getId()))).andExpect(status().isNoContent());

        assertThat(auditRow(store, "USER_DEACTIVATE").json().path("email").asText()).isEqualTo(user.getEmail());
        AuditRows.Row resend = auditRow(store, "USER_INVITATION_RESEND");
        assertThat(resend.targetId()).isEqualTo(invited.getId());
        assertThat(resend.details()).doesNotContain(userRepository.findById(invited.getId()).orElseThrow().getInvitationToken());
        AuditRows.Row deleted = auditRow(store, "USER_DELETE");
        assertThat(deleted.targetId()).isEqualTo(user.getId());
        assertThat(deleted.json().path("email").asText()).isEqualTo(user.getEmail());
    }

    @Test
    @DisplayName("Los roles dejan sus permisos por nombre: los que tiene al crearlo, los que entran y salen, y los que tenía al borrarlo")
    void rolesRecordTheirPermissionsByName() throws Exception {
        List<Permission> permissions = permissionRepository.findAll();
        Permission first = permissions.get(0);
        Permission second = permissions.get(1);

        String created = mockMvc.perform(withAuth(post("/api/admin/roles"))
                        .contentType("application/json")
                        .content("{\"name\":\"Caja\",\"permissionIds\":[" + first.getId() + "]}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        long roleId = objectMapper.readTree(created).get("id").asLong();
        mockMvc.perform(withAuth(put("/api/admin/roles/" + roleId))
                        .contentType("application/json")
                        .content("{\"name\":\"Caja\",\"permissionIds\":[" + second.getId() + "]}"))
                .andExpect(status().isOk());
        mockMvc.perform(withAuth(delete("/api/admin/roles/" + roleId))).andExpect(status().isNoContent());

        assertThat(auditRow(store, "ROLE_CREATE").json().path("permissions").get(0).asText()).isEqualTo(first.getName());
        AuditRows.Row update = auditRow(store, "ROLE_UPDATE");
        assertThat(update.change("permissions").path("added").get(0).asText()).isEqualTo(second.getName());
        assertThat(update.change("permissions").path("removed").get(0).asText()).isEqualTo(first.getName());
        assertThat(update.change("name").isMissingNode()).isTrue();
        AuditRows.Row deleted = auditRow(store, "ROLE_DELETE");
        assertThat(deleted.targetId()).isEqualTo(roleId);
        assertThat(deleted.json().path("permissions").get(0).asText()).isEqualTo(second.getName());
    }

    private MockMultipartHttpServletRequestBuilder update(User user, Role role, boolean active) {
        MockMultipartHttpServletRequestBuilder request = multipart("/api/admin/users/" + user.getId());
        request.with(req -> {
            req.setMethod("PUT");
            return req;
        });
        withAuth(request);
        request.param("name", user.getName())
                .param("email", user.getEmail())
                .param("roleId", String.valueOf(role.getId()))
                .param("active", String.valueOf(active))
                .param("sendInvitation", "false");
        return request;
    }

    // La tienda es nueva en cada test, así que el nombre basta para que el rol sea único.
    private Role role(String name) {
        Role role = new Role();
        role.setStore(store);
        role.setName(name);
        role.setPermissions(new HashSet<>());
        return roleRepository.save(role);
    }

    private <T extends AbstractMockHttpServletRequestBuilder<?>> T withAuth(T request) {
        request.header("Host", hostHeader(store)).header("Authorization", "Bearer " + token);
        return request;
    }
}
