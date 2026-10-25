package org.uvo.uvostore.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.uvo.uvostore.entity.security.User;

import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long>, JpaSpecificationExecutor<User> {

    // PROD-17b. findByEmail y existsByEmail sin tienda se quitaron (cero llamadores): el correo es único
    // por tienda. Los dos tokens siguen siendo globales a propósito: son secretos aleatorios y UNIQUE.
    Optional<User> findByStoreIdAndEmail(Long storeId, String email);
    Optional<User> findByInvitationToken(String token);
    Optional<User> findByPasswordResetToken(String token);

    /** PROD-23. Los administradores de una tienda, para revocarles el acceso al cerrarla. */
    List<User> findAllByStoreIdOrderById(Long storeId);
}
