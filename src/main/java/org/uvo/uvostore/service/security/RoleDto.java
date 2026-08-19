package org.uvo.uvostore.service.security;

import java.util.List;

public record RoleDto(Long id, String name, String guardName, List<PermissionDto> permissions) {
}
