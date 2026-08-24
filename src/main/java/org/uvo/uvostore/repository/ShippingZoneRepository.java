package org.uvo.uvostore.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.uvo.uvostore.entity.shipping.ShippingZone;

import java.util.List;

@Repository
public interface ShippingZoneRepository extends JpaRepository<ShippingZone, Long> {

    List<ShippingZone> findByIsActiveTrueOrderBySortOrderAsc();
    // ShippingZone::covers() checked region/commune membership inside a JSON column —
    // port as a native JSON_CONTAINS query (MySQL/Postgres) rather than a derived method.

    List<ShippingZone> findByStoreId(Long storeId);
    List<ShippingZone> findByStoreIdAndIsActiveTrueOrderBySortOrderAsc(Long storeId);
}
