package org.uvo.uvostore.service.security;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.uvo.uvostore.entity.security.Role;
import org.uvo.uvostore.repository.PermissionRepository;
import org.uvo.uvostore.repository.RoleRepository;
import org.uvo.uvostore.security.TenantContext;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.stream.Collectors;

@Service
public class RoleQueryServiceImpl implements RoleQueryService {

    private final RoleRepository roleRepository;
    private final PermissionRepository permissionRepository;

    public RoleQueryServiceImpl(RoleRepository roleRepository, PermissionRepository permissionRepository) {
        this.roleRepository = roleRepository;
        this.permissionRepository = permissionRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public List<RoleDto> listAll() {
        return roleRepository.findByStoreId(TenantContext.requireStoreId()).stream().map(this::toDto).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public RoleDto getById(Long id) {
        Role role = roleRepository.findById(id)
                .filter(r -> r.getStore().getId().equals(TenantContext.requireStoreId()))
                .orElseThrow(() -> new NoSuchElementException("Role " + id + " not found"));
        return toDto(role);
    }

    @Override
    @Transactional(readOnly = true)
    public Map<String, List<PermissionDto>> allPermissionsGrouped() {
        return permissionRepository.findAll().stream()
                .collect(Collectors.groupingBy(
                        p -> p.getName().contains(".") ? p.getName().substring(0, p.getName().indexOf('.')) : p.getName(),
                        LinkedHashMap::new,
                        Collectors.mapping(p -> new PermissionDto(p.getId(), p.getName()), Collectors.toList())
                ));
    }

    private RoleDto toDto(Role role) {
        List<PermissionDto> permissions = role.getPermissions().stream()
                .map(p -> new PermissionDto(p.getId(), p.getName()))
                .toList();
        return new RoleDto(role.getId(), role.getName(), role.getGuardName(), permissions);
    }
}
