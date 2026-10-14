package org.uvo.uvostore.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.uvo.uvostore.entity.settings.Setting;

import java.util.Optional;

public interface SettingRepository extends JpaRepository<Setting, Long> {

    // PROD-17b: findBySettingKey sin tienda se quitó (cero llamadores): cada tienda tiene su propia fila
    // por clave, y esa devolvería la de cualquiera.
    Optional<Setting> findByStoreIdAndSettingKey(Long storeId, String key);
}
