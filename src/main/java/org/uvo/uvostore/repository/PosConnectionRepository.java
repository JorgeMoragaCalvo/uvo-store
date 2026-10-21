package org.uvo.uvostore.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.uvo.uvostore.entity.pos.PosConnection;

import java.util.List;
import java.util.Optional;

public interface PosConnectionRepository extends JpaRepository<PosConnection, Long> {

    Optional<PosConnection> findByCompanyId(Long companyId);
    // PROD-17b: findByIsActiveTrue (las conexiones activas de todas las tiendas) se quitó: cero llamadores.
    Optional<PosConnection> findByStoreId(Long storeId);

    // PROD-13b: una existencia y no findByStoreId, que devuelve Optional sobre store_id —que no es único: una
    // tienda puede tener una conexión por empresa— y lanzaría con dos. Se usa dentro de la transacción del
    // pago, donde esa excepción desharía la confirmación.
    @Query("select count(c) > 0 from PosConnection c where c.store.id = :storeId and c.isActive = true")
    boolean existsActiveForStore(@Param("storeId") Long storeId);

    /**
     * POS-4. Las conexiones activas de todas las tiendas, para la conciliación programada. Con la tienda, porque
     * el job la necesita fuera de toda sesión para preguntar si la integración está encendida.
     */
    @Query("select c from PosConnection c join fetch c.store where c.isActive = true order by c.id")
    List<PosConnection> findAllActive();

    /** POS-4. Las de una tienda, para la resincronización del panel. Una lista: store_id no es único. */
    @Query("select c from PosConnection c join fetch c.store where c.store.id = :storeId and c.isActive = true order by c.id")
    List<PosConnection> findActiveByStoreId(@Param("storeId") Long storeId);
}
