package org.uvo.uvostore.service.security;

import org.uvo.uvostore.entity.security.User;

public interface UserService {
    User createUser(UserCreateCommand command);
    User updateUser(Long id, UserCreateCommand command);

    /**
     * PROD-06. Reenvía la invitación con un token nuevo, que invalida el anterior. Rechaza si la cuenta
     * ya tiene contraseña, si está desactivada, o si no ha pasado el intervalo mínimo desde el último
     * envío.
     */
    User resendInvitation(Long id);
    void deactivateUser(Long id);
    void assignRoles(Long userId, java.util.Set<Long> roleIds);

    // Ports UserIndex::toggleStatus()/deleteUser() — self-protection ("can't deactivate/delete
    // yourself") is a controller-level concern (needs the authenticated principal), not enforced
    // here.
    User toggleActive(Long id);
    void deleteUser(Long id);
}
