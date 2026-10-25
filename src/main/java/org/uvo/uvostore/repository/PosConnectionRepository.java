package org.uvo.uvostore.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.uvo.uvostore.entity.pos.PosConnection;

import java.util.Collection;
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
     *
     * <p>PROD-23. Solo de tiendas activas: con la tienda suspendida —por ejemplo porque la empresa dio de baja el
     * módulo en el POS— la conciliación se pausa, en vez de preguntarle al POS cada 15 minutos por una tienda que no
     * vende. Al reactivarla, la próxima pasada la pone al día. Las entradas del POS siguen funcionando mientras tanto.
     */
    @Query("""
            select c from PosConnection c join fetch c.store s
            where c.isActive = true and s.status = org.uvo.uvostore.entity.tenant.enums.StoreStatus.ACTIVE
            order by c.id
            """)
    List<PosConnection> findAllActive();

    /** POS-4. Las de una tienda, para la resincronización del panel. Una lista: store_id no es único. */
    @Query("select c from PosConnection c join fetch c.store where c.store.id = :storeId and c.isActive = true order by c.id")
    List<PosConnection> findActiveByStoreId(@Param("storeId") Long storeId);

    // PROD-23. Las tres que siguen no cargan la entidad a propósito: cargarla descifra el token y el secreto
    // (EncryptedStringConverter), y una sola conexión que no se pudiera descifrar —una clave rotada— tumbaría la lista
    // entera de la plataforma, o el cierre de la tienda que justamente se quiere dar de baja.

    /** Lo que la lista de la plataforma muestra de las conexiones de varias tiendas, en una sola consulta. */
    @Query("""
            select c.store.id as storeId, c.companyId as companyId, c.companyName as companyName, c.isActive as active
            from PosConnection c where c.store.id in :storeIds order by c.id
            """)
    List<PosLinkView> findLinksByStoreIdIn(@Param("storeIds") Collection<Long> storeIds);

    /** Las empresas conectadas a una tienda, activas o no: el cierre las nombra en la auditoría. */
    @Query("select c.companyId from PosConnection c where c.store.id = :storeId order by c.companyId")
    List<Long> findCompanyIdsByStoreId(@Param("storeId") Long storeId);

    /** El cierre borra las conexiones: así la empresa (company_id es UNIQUE) puede darse de alta otra vez. */
    @Modifying(flushAutomatically = true)
    @Query("delete from PosConnection c where c.store.id = :storeId")
    int deleteAllForStore(@Param("storeId") Long storeId);

    interface PosLinkView {
        Long getStoreId();

        Long getCompanyId();

        String getCompanyName();

        Boolean getActive();
    }
}
