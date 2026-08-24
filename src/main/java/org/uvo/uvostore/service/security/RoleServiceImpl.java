package org.uvo.uvostore.service.security;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.uvo.uvostore.entity.security.Permission;
import org.uvo.uvostore.entity.security.Role;
import org.uvo.uvostore.repository.PermissionRepository;
import org.uvo.uvostore.repository.RoleRepository;
import org.uvo.uvostore.security.TenantContext;

import java.util.HashSet;
import java.util.NoSuchElementException;
import java.util.Set;

@Service
public class RoleServiceImpl implements RoleService {

    private final RoleRepository roleRepository;
    private final PermissionRepository permissionRepository;

    public RoleServiceImpl(RoleRepository roleRepository, PermissionRepository permissionRepository) {
        this.roleRepository = roleRepository;
        this.permissionRepository = permissionRepository;
    }

    @Override
    @Transactional
    public Role createRole(RoleCommand command) {
        Role role = new Role();
        role.setStore(TenantContext.requireCurrent());
        role.setName(command.name());
        if (command.permissionIds() != null) {
            role.setPermissions(resolvePermissions(command.permissionIds()));
        }
        return roleRepository.save(role);
    }

    @Override
    @Transactional
    public Role updateRole(Long id, RoleCommand command) {
        Role role = roleRepository.findById(id)
                .filter(r -> r.getStore().getId().equals(TenantContext.requireStoreId()))
                .orElseThrow(() -> new NoSuchElementException("Role " + id + " not found"));
        role.setName(command.name());
        if (command.permissionIds() != null) {
            role.setPermissions(resolvePermissions(command.permissionIds()));
        }
        return roleRepository.save(role);
    }

    @Override
    @Transactional
    public void deleteRole(Long id) {
        Role role = roleRepository.findById(id)
                .filter(r -> r.getStore().getId().equals(TenantContext.requireStoreId()))
                .orElseThrow(() -> new NoSuchElementException("Role " + id + " not found"));
        roleRepository.delete(role);
    }

    private Set<Permission> resolvePermissions(Set<Long> permissionIds) {
        Set<Permission> permissions = new HashSet<>();
        for (Long permissionId : permissionIds) {
            permissions.add(permissionRepository.findById(permissionId)
                    .orElseThrow(() -> new NoSuchElementException("Permission " + permissionId + " not found")));
        }
        return permissions;
    }
}
