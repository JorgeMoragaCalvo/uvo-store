package org.uvo.uvostore.platform;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.uvo.uvostore.entity.security.Role;
import org.uvo.uvostore.entity.security.User;
import org.uvo.uvostore.entity.tenant.Store;
import org.uvo.uvostore.entity.tenant.enums.StoreStatus;
import org.uvo.uvostore.service.platform.StoreOwnerInvitedEvent;
import org.uvo.uvostore.support.AuditRows;
import org.uvo.uvostore.support.IntegrationTestSupport;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * PROD-23. Devolverle el acceso a su tienda al dueño que lo perdió: olvidó la contraseña, nunca le llegó la invitación,
 * otro administrador lo desactivó o le quitó el rol, o su correo ya no sirve. La cuenta queda como recién invitada.
 *
 * <p>El correo después del commit está en {@code OwnerAccessRecoveryCommitTest}: aquí todo se deshace y los oyentes
 * {@code AFTER_COMMIT} no corren, así que solo se ve el evento.
 */
@RecordApplicationEvents
class OwnerAccessRecoveryTest extends IntegrationTestSupport {

    private static final String NEW_PASSWORD = "password123456";

    @Value("${app.platform-api-key}")
    private String platformKey;

    @Autowired
    private ApplicationEvents events;
    @PersistenceContext
    private EntityManager entityManager;

    @Test
    @DisplayName("Un dueño desactivado y sin rol queda activo, sin contraseña, sin sesiones, con su rol y una invitación nueva")
    void theOwnerGetsAFreshInvitation() throws Exception {
        Store store = createStore("rec-full");
        User owner = owner(store);
        Role ownerRole = ownerRole(store, Set.of("products.view"));
        String oldToken = loginAdmin(store, owner);
        int oldVersion = owner.getTokenVersion();
        // Lo que pudo haberle hecho otro administrador: desactivarlo y quitarle el rol.
        owner.setActive(false);
        owner.getRoles().clear();
        userRepository.saveAndFlush(owner);

        mockMvc.perform(recovery(store).header("X-Platform-Operator", "op-acceso"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.owner.active").value(true))
                .andExpect(jsonPath("$.owner.invitationPending").value(true));

        entityManager.flush();
        entityManager.clear();
        User recovered = userRepository.findById(owner.getId()).orElseThrow();
        String invitationToken = recovered.getInvitationToken();
        assertThat(recovered.isActive()).isTrue();
        assertThat(recovered.isAdmin()).isTrue();
        assertThat(recovered.getPassword()).isNull();
        assertThat(recovered.getInvitationToken()).isNotNull().hasSize(64);
        assertThat(recovered.getInvitationSentAt()).isAfter(Instant.now().minus(1, ChronoUnit.MINUTES));
        assertThat(recovered.getInvitationAcceptedAt()).isNull();
        assertThat(recovered.getTokenVersion()).isEqualTo(oldVersion + 1);
        // El rol que ya existía, con sus permisos tal como los dejó el comerciante.
        assertThat(recovered.getRoles()).extracting(Role::getId).containsExactly(ownerRole.getId());
        assertThat(roleRepository.findById(ownerRole.getId()).orElseThrow().getPermissions())
                .extracting(p -> p.getName()).containsExactly("products.view");

        // La sesión vieja ya no sirve, y la contraseña vieja tampoco.
        mockMvc.perform(get("/api/admin/products").header("Host", hostHeader(store))
                        .header("Authorization", "Bearer " + oldToken))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(login(store, recovered.getEmail(), TEST_PASSWORD))
                .andExpect(status().isUnauthorized());

        // La invitación nueva sí: elige su contraseña y entra.
        mockMvc.perform(post("/api/admin/auth/accept-invitation")
                        .header("Host", hostHeader(store))
                        .contentType("application/json")
                        .content("{\"token\":\"" + invitationToken + "\",\"password\":\"" + NEW_PASSWORD + "\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(login(store, recovered.getEmail(), NEW_PASSWORD))
                .andExpect(status().isOk());

        assertThat(events.stream(StoreOwnerInvitedEvent.class))
                .singleElement().satisfies(event -> assertThat(event.userId()).isEqualTo(owner.getId()));
        AuditRows.Row row = auditRow(store, "STORE_OWNER_ACCESS_RECOVERY");
        assertThat(row.actorType()).isEqualTo("PLATFORM");
        assertThat(row.actorLabel()).isEqualTo("op-acceso");
        assertThat(row.targetType()).isEqualTo("USER");
        assertThat(row.targetId()).isEqualTo(owner.getId());
        JsonNode details = row.json();
        assertThat(details.path("reactivated").asBoolean()).isTrue();
        assertThat(details.path("passwordCleared").asBoolean()).isTrue();
        assertThat(details.path("roleRestored").asBoolean()).isTrue();
        assertThat(details.path("sessionsRevoked").asBoolean()).isTrue();
        assertThat(details.path("changes").path("email").isMissingNode()).isTrue();
        assertThat(row.details()).doesNotContain(invitationToken);
    }

    @Test
    @DisplayName("Si alguien borró el rol de dueño, se vuelve a crear con todos los permisos")
    void aDeletedOwnerRoleIsRecreated() throws Exception {
        Store store = createStore("rec-role");
        User owner = owner(store);

        mockMvc.perform(recovery(store)).andExpect(status().isOk());

        entityManager.flush();
        entityManager.clear();
        Role role = userRepository.findById(owner.getId()).orElseThrow().getRoles().stream()
                .filter(candidate -> "Administrador".equals(candidate.getName()))
                .findFirst().orElseThrow();
        assertThat(role.getStore().getId()).isEqualTo(store.getId());
        assertThat(role.getPermissions()).hasSize((int) permissionRepository.count());
    }

    @Test
    @DisplayName("Con otro correo, la invitación va al nuevo; un correo de otro admin de la tienda se rechaza")
    void theEmailCanBeChangedButNotToAnotherAdmins() throws Exception {
        Store store = createStore("rec-email");
        User owner = owner(store);
        User other = createAdmin(store, "rec-other");
        String previous = owner.getEmail();
        String fresh = "nuevo-dueno-" + nextSeq() + "@test.local";

        mockMvc.perform(recovery(store).content("{\"email\":\"" + other.getEmail() + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("otro administrador")));
        mockMvc.perform(recovery(store).content("{\"email\":\"no-es-un-correo\"}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(recovery(store).content("{\"email\":\"" + fresh + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.owner.email").value(fresh));

        AuditRows.Row row = auditRow(store, "STORE_OWNER_ACCESS_RECOVERY");
        assertThat(row.change("email").path("from").asText()).isEqualTo(previous);
        assertThat(row.change("email").path("to").asText()).isEqualTo(fresh);
    }

    @Test
    @DisplayName("Con el mismo correo hay que esperar entre envíos; cambiar el correo no espera")
    void theCooldownAppliesOnlyToTheSameEmail() throws Exception {
        Store store = createStore("rec-cooldown");
        owner(store);

        mockMvc.perform(recovery(store)).andExpect(status().isOk());
        mockMvc.perform(recovery(store))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("espera")));
        mockMvc.perform(recovery(store).content("{\"email\":\"corregido-" + nextSeq() + "@test.local\"}"))
                .andExpect(status().isOk());

        assertThat(auditRows(store, "STORE_OWNER_ACCESS_RECOVERY")).hasSize(2);
    }

    @Test
    @DisplayName("Con la tienda suspendida, o sin dueño registrado, no se recupera nada")
    void needsAnActiveStoreWithAnOwner() throws Exception {
        Store suspended = createStore("rec-susp", StoreStatus.SUSPENDED);
        owner(suspended);
        Store ownerless = createStore("rec-noowner");

        mockMvc.perform(recovery(suspended))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("reactívala")));
        mockMvc.perform(recovery(ownerless))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("propietario")));

        assertThat(auditRows(suspended)).isEmpty();
        assertThat(auditRows(ownerless)).isEmpty();
    }

    /** Un dueño con contraseña, registrado como tal en la tienda. */
    private User owner(Store store) {
        User owner = createAdmin(store, "rec-owner");
        store.setOwnerUserId(owner.getId());
        storeRepository.save(store);
        return owner;
    }

    private Role ownerRole(Store store, Set<String> permissions) {
        Role role = new Role();
        role.setStore(store);
        role.setName("Administrador");
        role.setPermissions(new HashSet<>(permissionRepository.findAll().stream()
                .filter(p -> permissions.contains(p.getName())).toList()));
        return roleRepository.save(role);
    }

    private MockHttpServletRequestBuilder recovery(Store store) {
        return post("/api/platform/stores/" + store.getId() + "/owner/access-recovery")
                .header("X-Platform-Key", platformKey)
                .contentType("application/json");
    }

    private MockHttpServletRequestBuilder login(Store store, String email, String password) {
        return post("/api/admin/auth/login")
                .header("Host", hostHeader(store))
                .contentType("application/json")
                .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}");
    }
}
