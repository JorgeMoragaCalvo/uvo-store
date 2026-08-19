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

    Optional<Customer> findByEmail(String email);
    boolean existsByEmail(String email);
    Optional<Customer> findByInvitationToken(String token);

    // Admin\Customers\CustomerIndex::stats() — 'with_orders' / 'new_this_month'.
    @Query("SELECT COUNT(DISTINCT c) FROM Customer c JOIN c.orders o")
    long countWithOrders();

    long countByCreatedAtAfter(Instant since);
}
