package org.uvo.uvostore.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.uvo.uvostore.entity.payment.BankTransferSettings;

import java.util.Optional;

@Repository
public interface BankTransferSettingsRepository extends JpaRepository<BankTransferSettings, Long> {

    Optional<BankTransferSettings> findByStoreId(Long storeId);
}
