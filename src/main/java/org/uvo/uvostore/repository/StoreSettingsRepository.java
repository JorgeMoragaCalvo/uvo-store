package org.uvo.uvostore.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.uvo.uvostore.entity.settings.StoreSettings;

import java.util.Optional;

public interface StoreSettingsRepository extends JpaRepository<StoreSettings, Long> {

    // Multi-tenant: one row per store now (UNIQUE(store_id) at the DB level), not a global singleton.
    // PROD-17b: findFirstByOrderByIdAsc (el singleton de Laravel) se quitó: sin llamadores, y con una
    // fila por tienda devolvería los ajustes de la primera que se creó, sea cual sea la de la petición.
    Optional<StoreSettings> findByStoreId(Long storeId);
}
