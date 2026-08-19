package org.uvo.uvostore.controller.settings;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.uvo.uvostore.service.settings.GeneralSettingsDto;
import org.uvo.uvostore.service.settings.SettingsService;

@RestController
@RequestMapping("/api/admin/settings/general")
public class SettingsController {

    private final SettingsService settingsService;

    public SettingsController(SettingsService settingsService) {
        this.settingsService = settingsService;
    }

    @GetMapping
    public GeneralSettingsDto show() {
        return settingsService.getGeneralSettings();
    }

    @PutMapping
    public GeneralSettingsDto update(@RequestBody GeneralSettingsDto request) {
        return settingsService.updateGeneralSettings(request);
    }
}
