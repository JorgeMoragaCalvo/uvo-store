package org.uvo.uvostore.service.settings;

import org.springframework.web.multipart.MultipartFile;

public interface StoreSettingsService {
    // Ports StoreSettings::current() — the singleton "row id=1, create if missing" pattern.
    StoreSettingsDto getCurrent();

    StoreSettingsDto update(StoreSettingsDto command, MultipartFile newLogo, MultipartFile newFavicon);
}
