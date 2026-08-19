package org.uvo.uvostore.service.security;

import java.util.List;
import java.util.Map;

// Ports Admin\Roles\RoleIndex.
public interface RoleQueryService {
    List<RoleDto> listAll();
    RoleDto getById(Long id);

    // Grouped by the segment before the first '.' in the permission name (e.g. "users.view" -> "users").
    Map<String, List<PermissionDto>> allPermissionsGrouped();
}
