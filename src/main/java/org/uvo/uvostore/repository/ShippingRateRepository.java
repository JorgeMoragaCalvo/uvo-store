package org.uvo.uvostore.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.uvo.uvostore.entity.shipping.ShippingRate;

import java.util.List;
import java.util.Set;

public interface ShippingRateRepository extends JpaRepository<ShippingRate, Long> {

    List<ShippingRate> findByMethodIdAndZoneIdAndIsActiveTrue(Long methodId, Long zoneId);
    List<ShippingRate> findByZoneIdAndIsActiveTrue(Long zoneId); // ShippingZone::getAvailableMethods()

    List<ShippingRate> findByStoreId(Long storeId);

    // GAP-18. Los métodos que el checkout puede cotizar por tarifa: con alguna tarifa activa en una zona activa,
    // los mismos filtros que ShippingRateServiceImpl.getAvailableOptions (el del método lo aplica quien llama).
    @Query("select distinct r.method.id from ShippingRate r "
            + "where r.store.id = :storeId and r.isActive = true and r.zone.isActive = true")
    Set<Long> findMethodIdsWithActiveRateForStore(@Param("storeId") Long storeId);
}
