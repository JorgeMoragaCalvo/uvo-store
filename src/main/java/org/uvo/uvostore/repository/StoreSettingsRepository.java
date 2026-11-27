package org.uvo.uvostore.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.uvo.uvostore.entity.settings.StoreSettings;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface StoreSettingsRepository extends JpaRepository<StoreSettings, Long> {

    // Multi-tenant: one row per store now (UNIQUE(store_id) at the DB level), not a global singleton.
    // PROD-17b: findFirstByOrderByIdAsc (el singleton de Laravel) se quitó: sin llamadores, y con una
    // fila por tienda devolvería los ajustes de la primera que se creó, sea cual sea la de la petición.
    Optional<StoreSettings> findByStoreId(Long storeId);

    /**
     * El RUT del vendedor de varias tiendas de una vez, para la lista de la plataforma: una consulta por página, no
     * una por tienda. Solo ese campo, por proyección: los ajustes llevan secretos cifrados que la lista no lee.
     */
    @Query("select s.store.id as storeId, s.legalTaxId as legalTaxId from StoreSettings s where s.store.id in :storeIds")
    List<LegalTaxIdView> findLegalTaxIdsByStoreIdIn(@Param("storeIds") Collection<Long> storeIds);

    interface LegalTaxIdView {
        Long getStoreId();

        String getLegalTaxId();
    }
}
