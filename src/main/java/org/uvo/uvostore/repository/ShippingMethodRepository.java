package org.uvo.uvostore.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.uvo.uvostore.entity.shipping.ShippingMethod;
import org.uvo.uvostore.service.shipping.ActiveShippingMethodRow;

import java.util.List;
import java.util.Optional;

public interface ShippingMethodRepository extends JpaRepository<ShippingMethod, Long> {

    // PROD-17b: findByCode y findByIsActiveTrueOrderBySortOrderAsc sin tienda se quitaron (cero llamadores).
    List<ShippingMethod> findByStoreId(Long storeId);
    Optional<ShippingMethod> findByStoreIdAndCode(Long storeId, String code);

    // PROD-23. El cierre de la tienda borra las credenciales de sus transportistas sin cargar los métodos, por la misma
    // razón que las pasarelas: cargarlos descifra. Un método sin credenciales guardadas también tiene la columna
    // llena (el mapa vacío se cifra igual), así que estos son los métodos con la columna escrita, no solo los que
    // tenían una clave.

    @Query("select m.code from ShippingMethod m where m.store.id = :storeId and m.apiCredentials is not null order by m.code")
    List<String> findCodesWithCredentialsForStore(@Param("storeId") Long storeId);

    // GAP-18. Los métodos activos de la tienda para la política de envíos, sin cargar la entidad (ver
    // ActiveShippingMethodRow).
    @Query("select new org.uvo.uvostore.service.shipping.ActiveShippingMethodRow("
            + "m.id, m.name, m.description, m.minDeliveryDays, m.maxDeliveryDays, m.hasApiIntegration, m.carrier) "
            + "from ShippingMethod m where m.store.id = :storeId and m.isActive = true order by m.sortOrder, m.id")
    List<ActiveShippingMethodRow> findActiveRowsForStore(@Param("storeId") Long storeId);

    @Modifying(flushAutomatically = true)
    @Query("update ShippingMethod m set m.apiCredentials = null where m.store.id = :storeId and m.apiCredentials is not null")
    int clearCredentialsForStore(@Param("storeId") Long storeId);
}
