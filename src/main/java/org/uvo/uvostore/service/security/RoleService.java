package org.uvo.uvostore.service.security;

import org.uvo.uvostore.entity.security.Role;

public interface RoleService {
    Role createRole(RoleCommand command);
    Role updateRole(Long id, RoleCommand command);
    void deleteRole(Long id);
}
