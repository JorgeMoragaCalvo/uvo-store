package org.uvo.uvostore.controller.admin.security;

import java.util.Set;

public record RoleRequest(String name, Set<Long> permissionIds) {
}
