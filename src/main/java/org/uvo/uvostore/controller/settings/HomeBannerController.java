package org.uvo.uvostore.controller.settings;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.uvo.uvostore.service.settings.HomeBannerCommand;
import org.uvo.uvostore.service.settings.HomeBannerDto;
import org.uvo.uvostore.service.settings.HomeBannerService;

import java.util.List;

@io.swagger.v3.oas.annotations.tags.Tag(name = "Banners (admin)", description = "CRUD de banners de la portada, JWT bearer con rol ADMIN")
@io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/api/admin/home/banners")
public class HomeBannerController {

    private final HomeBannerService homeBannerService;

    public HomeBannerController(HomeBannerService homeBannerService) {
        this.homeBannerService = homeBannerService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('banners.view')")
    public List<HomeBannerDto> index(@RequestParam(required = false) String search) {
        return homeBannerService.list(search);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('banners.view')")
    public HomeBannerDto show(@PathVariable Long id) {
        return homeBannerService.get(id);
    }

    @PostMapping(consumes = "multipart/form-data")
    @PreAuthorize("hasAuthority('banners.manage')")
    public HomeBannerDto create(@ModelAttribute HomeBannerRequest request) {
        return homeBannerService.create(toCommand(request));
    }

    @PutMapping(value = "/{id}", consumes = "multipart/form-data")
    @PreAuthorize("hasAuthority('banners.manage')")
    public HomeBannerDto update(@PathVariable Long id, @ModelAttribute HomeBannerRequest request) {
        return homeBannerService.update(id, toCommand(request));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('banners.manage')")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        homeBannerService.delete(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/toggle")
    @PreAuthorize("hasAuthority('banners.manage')")
    public HomeBannerDto toggle(@PathVariable Long id) {
        return homeBannerService.toggleActive(id);
    }

    @PostMapping("/reorder")
    @PreAuthorize("hasAuthority('banners.manage')")
    public ResponseEntity<Void> reorder(@RequestBody ReorderRequest request) {
        homeBannerService.reorder(request.orderedIds());
        return ResponseEntity.noContent().build();
    }

    private HomeBannerCommand toCommand(HomeBannerRequest request) {
        return new HomeBannerCommand(
                request.title(), request.subtitle(), request.description(), request.ctaText(), request.ctaLink(),
                request.ctaNewTab(), request.ctaSecondaryText(), request.ctaSecondaryLink(), request.textPosition(),
                request.textColor(), request.overlayColor(), request.overlayOpacity(), request.active(), request.sortOrder(),
                request.newImage(), request.newMobileImage()
        );
    }
}
