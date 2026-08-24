package org.uvo.uvostore.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.uvo.uvostore.entity.settings.StoreSettings;

import java.util.Optional;

public interface StoreSettingsRepository extends JpaRepository<StoreSettings, Long> {

    // Laravel used firstOrCreate([]) as a singleton-row pattern. Port as: repository
    // exposes findFirst(), and a @Service wraps "create the default row if absent"
    // rather than baking that logic into the repository.
    Optional<StoreSettings> findFirstByOrderByIdAsc();

    // Multi-tenant: one row per store now (UNIQUE(store_id) at the DB level), not a global singleton.
    Optional<StoreSettings> findByStoreId(Long storeId);
}
