package org.uvo.uvostore.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.uvo.uvostore.entity.shipping.ShippingRate;

import java.util.List;

public interface ShippingRateRepository extends JpaRepository<ShippingRate, Long> {

    List<ShippingRate> findByMethodIdAndZoneIdAndIsActiveTrue(Long methodId, Long zoneId);
    List<ShippingRate> findByZoneIdAndIsActiveTrue(Long zoneId); // ShippingZone::getAvailableMethods()
}
