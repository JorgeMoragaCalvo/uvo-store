package org.uvo.uvostore.controller.admin.security;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.uvo.uvostore.entity.security.Role;
import org.uvo.uvostore.service.security.PermissionDto;
import org.uvo.uvostore.service.security.RoleCommand;
import org.uvo.uvostore.service.security.RoleDto;
import org.uvo.uvostore.service.security.RoleQueryService;
import org.uvo.uvostore.service.security.RoleService;

import java.util.List;
import java.util.Map;

@io.swagger.v3.oas.annotations.tags.Tag(name = "Roles (admin)", description = "Roles y permisos de administradores, JWT bearer con rol ADMIN")
@io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/api/admin/roles")
public class AdminRoleController {

    private final RoleService roleService;
    private final RoleQueryService roleQueryService;

    public AdminRoleController(RoleService roleService, RoleQueryService roleQueryService) {
        this.roleService = roleService;
        this.roleQueryService = roleQueryService;
    }

    @GetMapping
    public List<RoleDto> index() {
        return roleQueryService.listAll();
    }

    @GetMapping("/{id}")
    public RoleDto show(@PathVariable Long id) {
        return roleQueryService.getById(id);
    }

    @GetMapping("/permissions")
    public Map<String, List<PermissionDto>> permissions() {
        return roleQueryService.allPermissionsGrouped();
    }

    @PostMapping
    public RoleDto create(@RequestBody RoleRequest request) {
        Role role = roleService.createRole(new RoleCommand(request.name(), request.permissionIds()));
        return roleQueryService.getById(role.getId());
    }

    @PutMapping("/{id}")
    public RoleDto update(@PathVariable Long id, @RequestBody RoleRequest request) {
        Role role = roleService.updateRole(id, new RoleCommand(request.name(), request.permissionIds()));
        return roleQueryService.getById(role.getId());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        roleService.deleteRole(id);
        return ResponseEntity.noContent().build();
    }
}
