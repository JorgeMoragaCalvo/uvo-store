package org.uvo.uvostore.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.uvo.uvostore.entity.pos.PosConnection;

import java.util.Optional;

public interface PosConnectionRepository extends JpaRepository<PosConnection, Long> {

    Optional<PosConnection> findByCompanyId(Long companyId);
    // PROD-17b: findByIsActiveTrue (las conexiones activas de todas las tiendas) se quitó: cero llamadores.
    Optional<PosConnection> findByStoreId(Long storeId);
}
