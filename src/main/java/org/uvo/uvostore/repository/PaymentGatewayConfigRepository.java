package org.uvo.uvostore.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.uvo.uvostore.entity.payment.PaymentGatewayConfig;
import org.uvo.uvostore.entity.payment.enums.PaymentGatewayType;

import java.util.List;
import java.util.Optional;

@Repository
public interface PaymentGatewayConfigRepository extends JpaRepository<PaymentGatewayConfig, Long> {

    List<PaymentGatewayConfig> findByStoreId(Long storeId);
    Optional<PaymentGatewayConfig> findByStoreIdAndGateway(Long storeId, PaymentGatewayType gateway);
}
