package org.uvo.uvostore.service.settings;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.uvo.uvostore.entity.settings.StoreSettings;
import org.uvo.uvostore.repository.StoreSettingsRepository;
import org.uvo.uvostore.security.TenantContext;
import org.uvo.uvostore.service.catalog.FileStorageService;

@Service
public class StoreSettingsServiceImpl implements StoreSettingsService {

    private final StoreSettingsRepository storeSettingsRepository;
    private final FileStorageService fileStorageService;

    public StoreSettingsServiceImpl(StoreSettingsRepository storeSettingsRepository, FileStorageService fileStorageService) {
        this.storeSettingsRepository = storeSettingsRepository;
        this.fileStorageService = fileStorageService;
    }

    @Override
    @Transactional
    public StoreSettingsDto getCurrent() {
        return toDto(currentOrCreate());
    }

    @Override
    @Transactional
    public StoreSettingsDto update(StoreSettingsDto command, MultipartFile newLogo, MultipartFile newFavicon) {
        StoreSettings settings = currentOrCreate();

        settings.setStoreName(command.storeName());
        settings.setStoreDescription(command.storeDescription());
        settings.setPrimaryColor(command.primaryColor());
        settings.setSecondaryColor(command.secondaryColor());
        settings.setAccentColor(command.accentColor());
        settings.setDarkColor(command.darkColor());
        settings.setShowHero(command.showHero());
        settings.setHeroAutoplaySpeed(command.heroAutoplaySpeed());
        settings.setShowCategories(command.showCategories());
        settings.setCategoriesTitle(command.categoriesTitle());
        settings.setCategoriesLimit(command.categoriesLimit());
        settings.setShowNewProducts(command.showNewProducts());
        settings.setNewProductsTitle(command.newProductsTitle());
        settings.setNewProductsLimit(command.newProductsLimit());
        settings.setNewProductsDays(command.newProductsDays());
        settings.setShowFeaturedProducts(command.showFeaturedProducts());
        settings.setFeaturedProductsTitle(command.featuredProductsTitle());
        settings.setFeaturedProductsLimit(command.featuredProductsLimit());
        settings.setShowDeals(command.showDeals());
        settings.setDealsTitle(command.dealsTitle());
        settings.setDealsLimit(command.dealsLimit());
        settings.setShowBenefits(command.showBenefits());
        settings.setBenefit1Icon(command.benefit1Icon());
        settings.setBenefit1Title(command.benefit1Title());
        settings.setBenefit1Description(command.benefit1Description());
        settings.setBenefit2Icon(command.benefit2Icon());
        settings.setBenefit2Title(command.benefit2Title());
        settings.setBenefit2Description(command.benefit2Description());
        settings.setBenefit3Icon(command.benefit3Icon());
        settings.setBenefit3Title(command.benefit3Title());
        settings.setBenefit3Description(command.benefit3Description());
        settings.setBenefit4Icon(command.benefit4Icon());
        settings.setBenefit4Title(command.benefit4Title());
        settings.setBenefit4Description(command.benefit4Description());
        settings.setContactEmail(command.contactEmail());
        settings.setContactPhone(command.contactPhone());
        settings.setWhatsappNumber(command.whatsappNumber());
        settings.setFacebookUrl(command.facebookUrl());
        settings.setInstagramUrl(command.instagramUrl());
        settings.setTwitterUrl(command.twitterUrl());
        settings.setTiktokUrl(command.tiktokUrl());
        settings.setMetaTitle(command.metaTitle());
        settings.setMetaDescription(command.metaDescription());
        settings.setMetaKeywords(command.metaKeywords());

        if (newLogo != null && !newLogo.isEmpty()) {
            if (settings.getStoreLogo() != null) {
                fileStorageService.delete(settings.getStoreLogo());
            }
            settings.setStoreLogo(fileStorageService.store(newLogo, "logos"));
        }
        if (newFavicon != null && !newFavicon.isEmpty()) {
            if (settings.getStoreFavicon() != null) {
                fileStorageService.delete(settings.getStoreFavicon());
            }
            settings.setStoreFavicon(fileStorageService.store(newFavicon, "favicons"));
        }

        return toDto(storeSettingsRepository.save(settings));
    }

    private StoreSettings currentOrCreate() {
        return storeSettingsRepository.findByStoreId(TenantContext.requireStoreId()).orElseGet(() -> {
            StoreSettings settings = new StoreSettings();
            settings.setStore(TenantContext.requireCurrent());
            return storeSettingsRepository.save(settings);
        });
    }

    private StoreSettingsDto toDto(StoreSettings s) {
        return new StoreSettingsDto(
                s.getId(), s.getStoreName(), s.getStoreDescription(),
                fileStorageService.publicUrl(s.getStoreLogo()), fileStorageService.publicUrl(s.getStoreFavicon()),
                s.getPrimaryColor(), s.getSecondaryColor(), s.getAccentColor(), s.getDarkColor(),
                s.isShowHero(), s.getHeroAutoplaySpeed(), s.isShowCategories(), s.getCategoriesTitle(), s.getCategoriesLimit(),
                s.isShowNewProducts(), s.getNewProductsTitle(), s.getNewProductsLimit(), s.getNewProductsDays(),
                s.isShowFeaturedProducts(), s.getFeaturedProductsTitle(), s.getFeaturedProductsLimit(),
                s.isShowDeals(), s.getDealsTitle(), s.getDealsLimit(), s.isShowBenefits(),
                s.getBenefit1Icon(), s.getBenefit1Title(), s.getBenefit1Description(),
                s.getBenefit2Icon(), s.getBenefit2Title(), s.getBenefit2Description(),
                s.getBenefit3Icon(), s.getBenefit3Title(), s.getBenefit3Description(),
                s.getBenefit4Icon(), s.getBenefit4Title(), s.getBenefit4Description(),
                s.getContactEmail(), s.getContactPhone(), s.getWhatsappNumber(),
                s.getFacebookUrl(), s.getInstagramUrl(), s.getTwitterUrl(), s.getTiktokUrl(),
                s.getMetaTitle(), s.getMetaDescription(), s.getMetaKeywords()
        );
    }
}
