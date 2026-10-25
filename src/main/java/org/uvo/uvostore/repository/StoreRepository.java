package org.uvo.uvostore.repository;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.uvo.uvostore.entity.tenant.Store;

import java.util.Optional;

// PROD-23: JpaSpecificationExecutor para la lista y la búsqueda de la plataforma.
@Repository
public interface StoreRepository extends JpaRepository<Store, Long>, JpaSpecificationExecutor<Store> {
    Optional<Store> findBySlug(String slug);
    Optional<Store> findByDomain(String domain);
    boolean existsBySlug(String slug);
    boolean existsByDomain(String domain);

    /**
     * PROD-23. La tienda con su fila bloqueada hasta el fin de la transacción ({@code for no key update} en
     * PostgreSQL). Cada cambio de estado pasa por aquí, para que una suspensión y un cierre simultáneos no se pisen ni
     * decidan sobre un estado que ya cambió.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from Store s where s.id = :id")
    Optional<Store> findLockedById(@Param("id") Long id);
}
