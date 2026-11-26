package org.uvo.uvostore.controller.admin.catalog;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.uvo.uvostore.service.catalog.ProductDto;
import org.uvo.uvostore.service.catalog.ProductQueryService;
import org.uvo.uvostore.service.catalog.ProductVariationService;
import org.uvo.uvostore.service.catalog.VariationCommand;
import org.uvo.uvostore.service.pos.PosVariantLinkService;

import java.util.List;

@io.swagger.v3.oas.annotations.tags.Tag(name = "Variaciones de producto (admin)", description = "CRUD de variaciones de un producto, JWT bearer con rol ADMIN")
@io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/api/admin/products/{productId}/variations")
public class AdminProductVariationController {

    private final ProductVariationService productVariationService;
    private final ProductQueryService productQueryService;
    private final PosVariantLinkService linkService;

    public AdminProductVariationController(ProductVariationService productVariationService,
                                           ProductQueryService productQueryService, PosVariantLinkService linkService) {
        this.productVariationService = productVariationService;
        this.productQueryService = productQueryService;
        this.linkService = linkService;
    }

    @PostMapping
    @PreAuthorize("hasAuthority('products.manage')")
    public ProductDto add(@PathVariable Long productId, @RequestBody VariationRequest request) {
        productVariationService.addVariation(productId, toCommand(request));
        return productQueryService.getById(productId);
    }

    @PutMapping("/{variationId}")
    @PreAuthorize("hasAuthority('products.manage')")
    public ProductDto update(@PathVariable Long productId, @PathVariable Long variationId, @RequestBody VariationRequest request) {
        productVariationService.updateVariation(variationId, toCommand(request));
        return productQueryService.getById(productId);
    }

    @DeleteMapping("/{variationId}")
    @PreAuthorize("hasAuthority('products.manage')")
    public ResponseEntity<Void> delete(@PathVariable Long productId, @PathVariable Long variationId) {
        productVariationService.deleteVariation(variationId);
        return ResponseEntity.noContent().build();
    }

    /** IMP-08. Qué Producto del POS es cada variante del producto. Solo el panel: el storefront no ve ids del POS. */
    @GetMapping("/pos-links")
    @PreAuthorize("hasAuthority('products.view')")
    public List<PosVariantLinkService.Link> posLinks(@PathVariable Long productId) {
        return linkService.linksOf(productId);
    }

    /** IMP-08. Vincula la variante a un Producto del POS (contrato §4): su SKU, precio y stock pasan a ser del POS. */
    @PutMapping("/{variationId}/pos-link")
    @PreAuthorize("hasAuthority('products.manage')")
    public PosVariantLinkService.Link link(@PathVariable Long productId, @PathVariable Long variationId,
                                           @RequestBody PosLinkRequest request) {
        return linkService.link(productId, variationId, request.posProductId());
    }

    @DeleteMapping("/{variationId}/pos-link")
    @PreAuthorize("hasAuthority('products.manage')")
    public ResponseEntity<Void> unlink(@PathVariable Long productId, @PathVariable Long variationId) {
        linkService.unlink(productId, variationId);
        return ResponseEntity.noContent().build();
    }

    public record PosLinkRequest(Long posProductId) {
    }

    private VariationCommand toCommand(VariationRequest r) {
        return new VariationCommand(r.attributeValueIdsByAttributeId(), r.price(), r.stock(), r.sku());
    }
}
