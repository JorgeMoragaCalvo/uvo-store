package org.uvo.uvostore.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.uvo.uvostore.entity.security.Role;

import java.util.Optional;

public interface RoleRepository extends JpaRepository<Role, Long> {

    Optional<Role> findByName(String name);
    java.util.List<Role> findByStoreId(Long storeId);
}
