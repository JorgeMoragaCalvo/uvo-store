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

// Protected by PlatformApiKeyAuthFilter (X-Platform-Key header, and only from the private network
// since POS-6), not by the per-store JWT scheme the rest of /api/admin/** uses — there's no tenant
// yet when a store is being created, and this is only ever called by the operator team or by the
// UvoPOS backend, never by a client.
@io.swagger.v3.oas.annotations.tags.Tag(name = "Alta de tiendas (plataforma)", description = "Creación de tiendas nuevas por el equipo operador o por UvoPOS, header X-Platform-Key, solo desde la red privada")
@io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "platformApiKey")
@RestController
@RequestMapping("/api/platform/stores")
public class StoreOnboardingController {

    private final StoreOnboardingService storeOnboardingService;

    public StoreOnboardingController(StoreOnboardingService storeOnboardingService) {
        this.storeOnboardingService = storeOnboardingService;
    }

    // POS-6: lo llama también el backend de UvoPOS al activar el módulo, con el bloque `pos`. Es idempotente
    // por la empresa del POS: un reintento devuelve la tienda que ya existe, con created=false.
    @PostMapping
    public StoreOnboardingResponse create(@Valid @RequestBody StoreOnboardingRequest request) {
        StoreOnboardingRequest.PosLinkRequest pos = request.pos();
        return storeOnboardingService.createStore(new StoreOnboardingCommand(
                request.slug(), request.domain(), request.storeName(),
                request.adminName(), request.adminEmail(),
                pos == null ? null : new StoreOnboardingCommand.PosLink(pos.companyId(), pos.companyName(),
                        pos.apiUrl(), pos.apiKey(), pos.webhookSecret())
        ));
    }

    @PutMapping("/{id}/domain")
    public StoreOnboardingResponse updateDomain(@PathVariable Long id, @Valid @RequestBody UpdateStoreDomainRequest request) {
        return storeOnboardingService.updateDomain(id, request.domain());
    }

    // PROD-04. El dominio propio de una tienda no se usa en los enlaces que salen hasta que alguien
    // comprueba que apunta aquí; esto registra esa comprobación. Es operación de plataforma, igual que el
    // alta, y por eso vive detrás de X-Platform-Key y no del panel del cliente: el cliente no controla
    // su propio DNS desde aquí.
    @PutMapping("/{id}/domain/verified")
    public StoreOnboardingResponse setDomainVerified(@PathVariable Long id,
                                                      @Valid @RequestBody SetDomainVerifiedRequest request) {
        return storeOnboardingService.setDomainVerified(id, request.verified());
    }
}
