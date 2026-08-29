package org.uvo.uvostore.controller.settings;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.uvo.uvostore.service.settings.StoreSettingsDto;
import org.uvo.uvostore.service.settings.StoreSettingsService;

@io.swagger.v3.oas.annotations.tags.Tag(name = "Configuración de tienda (admin)", description = "Branding, banners home y ajustes visuales, JWT bearer con rol ADMIN")
@io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/api/admin/settings/store")
public class StoreSettingsController {

    private final StoreSettingsService storeSettingsService;

    public StoreSettingsController(StoreSettingsService storeSettingsService) {
        this.storeSettingsService = storeSettingsService;
    }

    @GetMapping
    public StoreSettingsDto show() {
        return storeSettingsService.getCurrent();
    }

    @PutMapping(consumes = "multipart/form-data")
    public StoreSettingsDto update(@ModelAttribute StoreSettingsUpdateRequest request) {
        StoreSettingsDto command = new StoreSettingsDto(
                null, request.storeName(), request.storeDescription(), null, null,
                request.primaryColor(), request.secondaryColor(), request.accentColor(), request.darkColor(),
                request.showHero(), request.heroAutoplaySpeed(), request.showCategories(), request.categoriesTitle(), request.categoriesLimit(),
                request.showNewProducts(), request.newProductsTitle(), request.newProductsLimit(), request.newProductsDays(),
                request.showFeaturedProducts(), request.featuredProductsTitle(), request.featuredProductsLimit(),
                request.showDeals(), request.dealsTitle(), request.dealsLimit(), request.showBenefits(),
                request.benefit1Icon(), request.benefit1Title(), request.benefit1Description(),
                request.benefit2Icon(), request.benefit2Title(), request.benefit2Description(),
                request.benefit3Icon(), request.benefit3Title(), request.benefit3Description(),
                request.benefit4Icon(), request.benefit4Title(), request.benefit4Description(),
                request.contactEmail(), request.contactPhone(), request.whatsappNumber(),
                request.facebookUrl(), request.instagramUrl(), request.twitterUrl(), request.tiktokUrl(),
                request.metaTitle(), request.metaDescription(), request.metaKeywords()
        );
        return storeSettingsService.update(command, request.newLogo(), request.newFavicon());
    }
}
