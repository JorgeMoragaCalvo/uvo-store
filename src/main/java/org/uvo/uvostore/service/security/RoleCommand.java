package org.uvo.uvostore.service.security;

import java.util.Set;

public record RoleCommand(String name, Set<Long> permissionIds) {
}
