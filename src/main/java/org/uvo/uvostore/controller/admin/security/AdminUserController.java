package org.uvo.uvostore.controller.admin.security;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.uvo.uvostore.entity.security.User;
import org.uvo.uvostore.security.AuthPrincipal;
import org.uvo.uvostore.service.security.UserCreateCommand;
import org.uvo.uvostore.service.security.UserDto;
import org.uvo.uvostore.service.security.UserQueryService;
import org.uvo.uvostore.service.security.UserService;

import java.util.Set;

@io.swagger.v3.oas.annotations.tags.Tag(name = "Usuarios (admin)", description = "Administradores de la tienda, JWT bearer con rol ADMIN")
@io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/api/admin/users")
public class AdminUserController {

    private final UserService userService;
    private final UserQueryService userQueryService;

    public AdminUserController(UserService userService, UserQueryService userQueryService) {
        this.userService = userService;
        this.userQueryService = userQueryService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('users.view')")
    public Page<UserDto> index(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) Long roleId,
            @RequestParam(required = false) Boolean active,
            @RequestParam(defaultValue = "createdAt") String sortField,
            @RequestParam(defaultValue = "desc") String sortDirection,
            @RequestParam(defaultValue = "1") int page
    ) {
        Sort.Direction direction = "asc".equalsIgnoreCase(sortDirection) ? Sort.Direction.ASC : Sort.Direction.DESC;
        return userQueryService.search(search, roleId, active, PageRequest.of(Math.max(page - 1, 0), 15, Sort.by(direction, sortField)));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('users.view')")
    public UserDto show(@PathVariable Long id) {
        return userQueryService.getById(id);
    }

    @PostMapping(consumes = "multipart/form-data")
    @PreAuthorize("hasAuthority('users.manage')")
    public UserDto create(@ModelAttribute UserRequest request) {
        User user = userService.createUser(toCommand(request));
        return userQueryService.getById(user.getId());
    }

    @PutMapping(value = "/{id}", consumes = "multipart/form-data")
    @PreAuthorize("hasAuthority('users.manage')")
    public UserDto update(@PathVariable Long id, @ModelAttribute UserRequest request) {
        User user = userService.updateUser(id, toCommand(request));
        return userQueryService.getById(user.getId());
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('users.manage')")
    public ResponseEntity<Void> delete(@PathVariable Long id, Authentication authentication) {
        guardAgainstSelf(id, authentication, "No puedes eliminarte a ti mismo");
        userService.deleteUser(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/toggle-status")
    @PreAuthorize("hasAuthority('users.manage')")
    public UserDto toggleStatus(@PathVariable Long id, Authentication authentication) {
        guardAgainstSelf(id, authentication, "No puedes desactivarte a ti mismo");
        User user = userService.toggleActive(id);
        return userQueryService.getById(user.getId());
    }

    private void guardAgainstSelf(Long id, Authentication authentication, String message) {
        AuthPrincipal principal = (AuthPrincipal) authentication.getPrincipal();
        if (principal.id().equals(id)) {
            throw new AccessDeniedException(message);
        }
    }

    private UserCreateCommand toCommand(UserRequest r) {
        return new UserCreateCommand(
                r.name(), r.email(), r.password(), r.phone(), true, r.active(), r.notes(), r.sendInvitation(),
                r.avatar(), r.roleId() == null ? Set.of() : Set.of(r.roleId())
        );
    }
}
