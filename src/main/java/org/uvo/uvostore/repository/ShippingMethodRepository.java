package org.uvo.uvostore.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.uvo.uvostore.entity.shipping.ShippingMethod;

import java.util.List;
import java.util.Optional;

public interface ShippingMethodRepository extends JpaRepository<ShippingMethod, Long> {

    Optional<ShippingMethod> findByCode(String code);
    List<ShippingMethod> findByIsActiveTrueOrderBySortOrderAsc();
}
