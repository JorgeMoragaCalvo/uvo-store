package org.uvo.uvostore.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.uvo.uvostore.entity.settings.Setting;

import java.util.Optional;

public interface SettingRepository extends JpaRepository<Setting, Long> {

    Optional<Setting> findBySettingKey(String key); // Setting::get()/set()/has()
    Optional<Setting> findByStoreIdAndSettingKey(Long storeId, String key);
}
