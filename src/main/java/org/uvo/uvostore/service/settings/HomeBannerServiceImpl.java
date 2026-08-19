package org.uvo.uvostore.service.settings;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.uvo.uvostore.entity.settings.HomeBanner;
import org.uvo.uvostore.entity.settings.enums.TextColor;
import org.uvo.uvostore.entity.settings.enums.TextPosition;
import org.uvo.uvostore.repository.HomeBannerRepository;
import org.uvo.uvostore.service.catalog.FileStorageService;

import java.util.Comparator;
import java.util.List;
import java.util.NoSuchElementException;

@Service
public class HomeBannerServiceImpl implements HomeBannerService {

    private final HomeBannerRepository homeBannerRepository;
    private final FileStorageService fileStorageService;

    public HomeBannerServiceImpl(HomeBannerRepository homeBannerRepository, FileStorageService fileStorageService) {
        this.homeBannerRepository = homeBannerRepository;
        this.fileStorageService = fileStorageService;
    }

    @Override
    @Transactional(readOnly = true)
    public List<HomeBannerDto> list(String search) {
        return homeBannerRepository.findAll().stream()
                .filter(b -> search == null || search.isBlank()
                        || containsIgnoreCase(b.getTitle(), search) || containsIgnoreCase(b.getSubtitle(), search))
                .sorted(Comparator.comparingInt(HomeBanner::getSortOrder)
                        .thenComparing(Comparator.comparing(HomeBanner::getCreatedAt).reversed()))
                .map(this::toDto)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public HomeBannerDto get(Long id) {
        return toDto(findOrThrow(id));
    }

    @Override
    @Transactional
    public HomeBannerDto create(HomeBannerCommand command) {
        if (command.newImage() == null || command.newImage().isEmpty()) {
            throw new IllegalArgumentException("La imagen es obligatoria");
        }

        HomeBanner banner = new HomeBanner();
        applyCommonFields(banner, command);
        banner.setImage(fileStorageService.store(command.newImage(), "banners"));
        if (command.newMobileImage() != null && !command.newMobileImage().isEmpty()) {
            banner.setMobileImage(fileStorageService.store(command.newMobileImage(), "banners/mobile"));
        }

        return toDto(homeBannerRepository.save(banner));
    }

    @Override
    @Transactional
    public HomeBannerDto update(Long id, HomeBannerCommand command) {
        HomeBanner banner = findOrThrow(id);
        applyCommonFields(banner, command);

        if (command.newImage() != null && !command.newImage().isEmpty()) {
            if (banner.getImage() != null) {
                fileStorageService.delete(banner.getImage());
            }
            banner.setImage(fileStorageService.store(command.newImage(), "banners"));
        }
        if (command.newMobileImage() != null && !command.newMobileImage().isEmpty()) {
            if (banner.getMobileImage() != null) {
                fileStorageService.delete(banner.getMobileImage());
            }
            banner.setMobileImage(fileStorageService.store(command.newMobileImage(), "banners/mobile"));
        }

        return toDto(homeBannerRepository.save(banner));
    }

    @Override
    @Transactional
    public void delete(Long id) {
        HomeBanner banner = findOrThrow(id);
        if (banner.getImage() != null) {
            fileStorageService.delete(banner.getImage());
        }
        if (banner.getMobileImage() != null) {
            fileStorageService.delete(banner.getMobileImage());
        }
        homeBannerRepository.delete(banner);
    }

    @Override
    @Transactional
    public HomeBannerDto toggleActive(Long id) {
        HomeBanner banner = findOrThrow(id);
        banner.setActive(!banner.isActive());
        return toDto(homeBannerRepository.save(banner));
    }

    @Override
    @Transactional
    public void reorder(List<Long> orderedIds) {
        for (int i = 0; i < orderedIds.size(); i++) {
            HomeBanner banner = findOrThrow(orderedIds.get(i));
            banner.setSortOrder(i + 1);
            homeBannerRepository.save(banner);
        }
    }

    private void applyCommonFields(HomeBanner banner, HomeBannerCommand command) {
        banner.setTitle(command.title());
        banner.setSubtitle(command.subtitle());
        banner.setDescription(command.description());
        banner.setCtaText(command.ctaText());
        banner.setCtaLink(command.ctaLink());
        banner.setCtaNewTab(command.ctaNewTab());
        banner.setCtaSecondaryText(command.ctaSecondaryText());
        banner.setCtaSecondaryLink(command.ctaSecondaryLink());
        banner.setTextPosition(TextPosition.valueOf(command.textPosition().toUpperCase()));
        banner.setTextColor(TextColor.valueOf(command.textColor().toUpperCase()));
        banner.setOverlayColor(command.overlayColor());
        banner.setOverlayOpacity(command.overlayOpacity());
        banner.setActive(command.active());
        banner.setSortOrder(command.sortOrder());
    }

    private HomeBanner findOrThrow(Long id) {
        return homeBannerRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Banner " + id + " not found"));
    }

    private boolean containsIgnoreCase(String value, String term) {
        return value != null && value.toLowerCase().contains(term.toLowerCase());
    }

    private HomeBannerDto toDto(HomeBanner b) {
        return new HomeBannerDto(
                b.getId(), b.getTitle(), b.getSubtitle(), b.getDescription(), b.getImage(), b.getMobileImage(),
                b.getCtaText(), b.getCtaLink(), b.isCtaNewTab(), b.getCtaSecondaryText(), b.getCtaSecondaryLink(),
                b.getTextPosition().name().toLowerCase(), b.getTextColor().name().toLowerCase(),
                b.getOverlayColor(), b.getOverlayOpacity(), b.isActive(), b.getSortOrder()
        );
    }
}
