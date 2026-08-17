package org.uvo.uvostore.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.uvo.uvostore.entity.customer.ShippingAddress;

import java.util.List;
import java.util.Optional;

public interface ShippingAddressRepository extends JpaRepository<ShippingAddress, Long> {

    List<ShippingAddress> findByCustomerId(Long customerId);
    Optional<ShippingAddress> findByCustomerIdAndIsDefaultTrue(Long customerId);
}
