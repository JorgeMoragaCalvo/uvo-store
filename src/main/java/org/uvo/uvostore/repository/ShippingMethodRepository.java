package org.uvo.uvostore.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.uvo.uvostore.entity.shipping.ShippingMethod;

import java.util.List;
import java.util.Optional;

public interface ShippingMethodRepository extends JpaRepository<ShippingMethod, Long> {

    // PROD-17b: findByCode y findByIsActiveTrueOrderBySortOrderAsc sin tienda se quitaron (cero llamadores).
    List<ShippingMethod> findByStoreId(Long storeId);
    Optional<ShippingMethod> findByStoreIdAndCode(Long storeId, String code);
}
