package org.uvo.uvostore.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.uvo.uvostore.entity.payment.PaymentGatewayConfig;
import org.uvo.uvostore.entity.payment.enums.PaymentGatewayType;

import java.util.List;
import java.util.Optional;

@Repository
public interface PaymentGatewayConfigRepository extends JpaRepository<PaymentGatewayConfig, Long> {

    List<PaymentGatewayConfig> findByStoreId(Long storeId);
    Optional<PaymentGatewayConfig> findByStoreIdAndGateway(Long storeId, PaymentGatewayType gateway);

    // PROD-23. El cierre de la tienda borra sus pasarelas sin cargarlas: cargarlas descifra las credenciales, y una
    // clave rotada dejaría la tienda sin poder cerrarse.

    @Query("select c.gateway from PaymentGatewayConfig c where c.store.id = :storeId order by c.gateway")
    List<PaymentGatewayType> findGatewaysByStoreId(@Param("storeId") Long storeId);

    @Modifying(flushAutomatically = true)
    @Query("delete from PaymentGatewayConfig c where c.store.id = :storeId")
    int deleteAllForStore(@Param("storeId") Long storeId);
}
