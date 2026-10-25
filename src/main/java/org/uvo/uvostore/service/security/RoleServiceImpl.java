package org.uvo.uvostore.service.security;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.uvo.uvostore.entity.audit.enums.AuditAction;
import org.uvo.uvostore.entity.audit.enums.AuditTargetType;
import org.uvo.uvostore.entity.security.Permission;
import org.uvo.uvostore.entity.security.Role;
import org.uvo.uvostore.repository.PermissionRepository;
import org.uvo.uvostore.repository.RoleRepository;
import org.uvo.uvostore.security.TenantContext;
import org.uvo.uvostore.service.audit.AuditDiff;
import org.uvo.uvostore.service.audit.AuditEntry;
import org.uvo.uvostore.service.audit.AuditTrail;

import java.util.HashSet;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.TreeSet;

@Service
public class RoleServiceImpl implements RoleService {

    private final RoleRepository roleRepository;
    private final PermissionRepository permissionRepository;
    private final AuditTrail auditTrail;

    public RoleServiceImpl(RoleRepository roleRepository, PermissionRepository permissionRepository,
                           AuditTrail auditTrail) {
        this.roleRepository = roleRepository;
        this.permissionRepository = permissionRepository;
        this.auditTrail = auditTrail;
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
        Role saved = roleRepository.save(role);
        auditTrail.record(AuditEntry.of(AuditAction.ROLE_CREATE, AuditTargetType.ROLE, saved.getId())
                .details(AuditDiff.create().with("name", saved.getName()).with("permissions", permissionNames(saved))));
        return saved;
    }

    @Override
    @Transactional
    public Role updateRole(Long id, RoleCommand command) {
        Role role = roleRepository.findById(id)
                .filter(r -> r.getStore().getId().equals(TenantContext.requireStoreId()))
                .orElseThrow(() -> new NoSuchElementException("Role " + id + " not found"));
        String previousName = role.getName();
        Set<String> previousPermissions = permissionNames(role);
        role.setName(command.name());
        if (command.permissionIds() != null) {
            role.setPermissions(resolvePermissions(command.permissionIds()));
        }
        Role saved = roleRepository.save(role);
        // PROD-18. Los permisos por nombre: qué entró y qué salió.
        AuditDiff diff = AuditDiff.create()
                .value("name", previousName, saved.getName())
                .members("permissions", previousPermissions, permissionNames(saved));
        if (diff.hasChanges()) {
            auditTrail.record(AuditEntry.of(AuditAction.ROLE_UPDATE, AuditTargetType.ROLE, saved.getId())
                    .details(diff.with("role", saved.getName())));
        }
        return saved;
    }

    @Override
    @Transactional
    public void deleteRole(Long id) {
        Role role = roleRepository.findById(id)
                .filter(r -> r.getStore().getId().equals(TenantContext.requireStoreId()))
                .orElseThrow(() -> new NoSuchElementException("Role " + id + " not found"));
        auditTrail.record(AuditEntry.of(AuditAction.ROLE_DELETE, AuditTargetType.ROLE, role.getId())
                .details(AuditDiff.create().with("name", role.getName()).with("permissions", permissionNames(role))));
        roleRepository.delete(role);
    }

    private static Set<String> permissionNames(Role role) {
        Set<String> names = new TreeSet<>();
        role.getPermissions().forEach(permission -> names.add(permission.getName()));
        return names;
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
