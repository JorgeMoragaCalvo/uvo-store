package org.uvo.uvostore.controller.settings;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.uvo.uvostore.service.settings.GeneralSettingsDto;
import org.uvo.uvostore.service.settings.HomeBannerDto;
import org.uvo.uvostore.service.settings.HomeBannerService;
import org.uvo.uvostore.service.settings.PublicStoreSettingsDto;
import org.uvo.uvostore.service.settings.SettingsService;
import org.uvo.uvostore.service.settings.StoreSettingsDto;
import org.uvo.uvostore.service.settings.StoreSettingsService;

import java.util.List;

// Public, unauthenticated read endpoints for the React storefront — mirrors what the admin-only
// StoreSettingsController/SettingsController/HomeBannerController already expose, filtered/merged
// down to what a customer-facing home page needs. See PublicStoreSettingsDto for what's excluded.
@RestController
@RequestMapping("/api/v1")
public class PublicStorefrontSettingsController {

    private final StoreSettingsService storeSettingsService;
    private final SettingsService settingsService;
    private final HomeBannerService homeBannerService;

    public PublicStorefrontSettingsController(
            StoreSettingsService storeSettingsService,
            SettingsService settingsService,
            HomeBannerService homeBannerService
    ) {
        this.storeSettingsService = storeSettingsService;
        this.settingsService = settingsService;
        this.homeBannerService = homeBannerService;
    }

    @GetMapping("/store-settings")
    public PublicStoreSettingsDto storeSettings() {
        StoreSettingsDto s = storeSettingsService.getCurrent();
        GeneralSettingsDto g = settingsService.getGeneralSettings();

        return new PublicStoreSettingsDto(
                s.storeName(), s.storeDescription(), s.storeLogo(), s.storeFavicon(),
                s.primaryColor(), s.secondaryColor(), s.accentColor(), s.darkColor(),
                s.showHero(), s.heroAutoplaySpeed(), s.showCategories(), s.categoriesTitle(), s.categoriesLimit(),
                s.showNewProducts(), s.newProductsTitle(), s.newProductsLimit(), s.newProductsDays(),
                s.showFeaturedProducts(), s.featuredProductsTitle(), s.featuredProductsLimit(),
                s.showDeals(), s.dealsTitle(), s.dealsLimit(), s.showBenefits(),
                s.benefit1Icon(), s.benefit1Title(), s.benefit1Description(),
                s.benefit2Icon(), s.benefit2Title(), s.benefit2Description(),
                s.benefit3Icon(), s.benefit3Title(), s.benefit3Description(),
                s.benefit4Icon(), s.benefit4Title(), s.benefit4Description(),
                s.contactEmail(), s.contactPhone(), s.whatsappNumber(),
                s.facebookUrl(), s.instagramUrl(), s.twitterUrl(), s.tiktokUrl(),
                s.metaTitle(), s.metaDescription(), s.metaKeywords(),
                g.currency(), g.currencySymbol(), g.taxRate(), g.pricesIncludeTax(),
                g.shippingEnabled(), g.defaultShippingCost(), g.freeShippingEnabled(), g.freeShippingThreshold()
        );
    }

    @GetMapping("/home-banners")
    public List<HomeBannerDto> homeBanners() {
        return homeBannerService.list(null).stream()
                .filter(HomeBannerDto::active)
                .toList();
    }
}
