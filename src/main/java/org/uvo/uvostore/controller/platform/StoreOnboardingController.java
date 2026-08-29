package org.uvo.uvostore.controller.platform;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.uvo.uvostore.service.platform.StoreOnboardingCommand;
import org.uvo.uvostore.service.platform.StoreOnboardingResponse;
import org.uvo.uvostore.service.platform.StoreOnboardingService;

// Protected by PlatformApiKeyAuthFilter (X-Platform-Key header), not by the per-store JWT scheme
// the rest of /api/admin/** uses — there's no tenant yet when a store is being created, and this
// is only ever called by the operator team, never by a client.
@io.swagger.v3.oas.annotations.tags.Tag(name = "Alta de tiendas (plataforma)", description = "Creación de tiendas nuevas por el equipo operador, header X-Platform-Key")
@io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "platformApiKey")
@RestController
@RequestMapping("/api/platform/stores")
public class StoreOnboardingController {

    private final StoreOnboardingService storeOnboardingService;

    public StoreOnboardingController(StoreOnboardingService storeOnboardingService) {
        this.storeOnboardingService = storeOnboardingService;
    }

    @PostMapping
    public StoreOnboardingResponse create(@Valid @RequestBody StoreOnboardingRequest request) {
        return storeOnboardingService.createStore(new StoreOnboardingCommand(
                request.slug(), request.domain(), request.storeName(),
                request.adminName(), request.adminEmail(), request.adminPassword()
        ));
    }

    @PutMapping("/{id}/domain")
    public StoreOnboardingResponse updateDomain(@PathVariable Long id, @Valid @RequestBody UpdateStoreDomainRequest request) {
        return storeOnboardingService.updateDomain(id, request.domain());
    }
}
