package org.uvo.uvostore.service.settings;

public interface SettingsService {
    GeneralSettingsDto getGeneralSettings();

    // Ports SettingsForm::save() — also validates pos_api_token against the "uvp_{digits}_{alnum}"
    // format and, when valid, upserts the single PosConnection row (id=1) from it, same as Laravel.
    GeneralSettingsDto updateGeneralSettings(GeneralSettingsUpdateRequest command);
}
