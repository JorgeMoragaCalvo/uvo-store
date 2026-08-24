package org.uvo.uvostore.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.uvo.uvostore.entity.security.User;

import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long>, JpaSpecificationExecutor<User> {

    Optional<User> findByEmail(String email);
    Optional<User> findByStoreIdAndEmail(Long storeId, String email);
    Optional<User> findByInvitationToken(String token);
    boolean existsByEmail(String email);
}
