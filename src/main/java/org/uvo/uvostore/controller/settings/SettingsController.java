package org.uvo.uvostore.controller.settings;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.uvo.uvostore.service.settings.GeneralSettingsDto;
import org.uvo.uvostore.service.settings.GeneralSettingsUpdateRequest;
import org.uvo.uvostore.service.settings.SettingsService;

@io.swagger.v3.oas.annotations.tags.Tag(name = "Configuración general (admin)", description = "Moneda, impuestos, envío y checkout, JWT bearer con rol ADMIN")
@io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/api/admin/settings/general")
public class SettingsController {

    private final SettingsService settingsService;

    public SettingsController(SettingsService settingsService) {
        this.settingsService = settingsService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('settings.view')")
    public GeneralSettingsDto show() {
        return settingsService.getGeneralSettings();
    }

    @PutMapping
    @PreAuthorize("hasAuthority('settings.manage')")
    public GeneralSettingsDto update(@RequestBody GeneralSettingsUpdateRequest request) {
        return settingsService.updateGeneralSettings(request);
    }
}
