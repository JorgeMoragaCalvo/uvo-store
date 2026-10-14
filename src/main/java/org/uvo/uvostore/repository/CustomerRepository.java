package org.uvo.uvostore.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import org.uvo.uvostore.entity.customer.Customer;

import java.time.Instant;
import java.util.Optional;

@Repository
public interface CustomerRepository extends JpaRepository<Customer, Long>, JpaSpecificationExecutor<Customer> {

    // PROD-17b. Aquí había cuatro consultas sin tienda (findByEmail, existsByEmail, countWithOrders,
    // countByCreatedAtAfter), sin un solo llamador. No se dejan "por si acaso": un correo es único por
    // tienda y no global, así que cualquiera de las dos primeras devolvería —o contaría— a un cliente de
    // otra tienda en cuanto alguien las usara sin pensarlo. Si hace falta una, se escribe con storeId.
    Optional<Customer> findByStoreIdAndEmail(Long storeId, String email);
    boolean existsByStoreIdAndEmail(Long storeId, String email);
    // Global a propósito: el token es aleatorio y UNIQUE, y AuthController comprueba que la tienda del
    // cliente es la del host antes de usarlo.
    Optional<Customer> findByInvitationToken(String token);

    // Admin\Customers\CustomerIndex::stats() — 'with_orders' / 'new_this_month'.
    long countByStoreId(Long storeId);

    @Query("SELECT COUNT(DISTINCT c) FROM Customer c JOIN c.orders o WHERE c.store.id = :storeId")
    long countWithOrdersByStoreId(@org.springframework.data.repository.query.Param("storeId") Long storeId);

    long countByStoreIdAndCreatedAtAfter(Long storeId, Instant since);
}
