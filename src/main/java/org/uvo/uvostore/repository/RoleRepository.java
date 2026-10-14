package org.uvo.uvostore.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.uvo.uvostore.entity.security.Role;

public interface RoleRepository extends JpaRepository<Role, Long> {

    // PROD-17b: findByName sin tienda se quitó (cero llamadores). Los nombres de rol se repiten entre
    // tiendas —el alta crea un 'Administrador' en cada una—, así que devolvería el de cualquiera.
    java.util.List<Role> findByStoreId(Long storeId);
}
