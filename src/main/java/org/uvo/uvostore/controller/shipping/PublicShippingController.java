package org.uvo.uvostore.controller.shipping;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.uvo.uvostore.service.shipping.ShippingCoverageDto;
import org.uvo.uvostore.service.shipping.ShippingCoverageService;

import java.util.List;

// A7: the storefront needs to know which regions and communes the store actually delivers to.
// Zone matching is an exact string comparison against free text an admin typed, so without this a
// customer could never supply a value that matches — and shipping silently priced at zero on every
// order. Public like the rest of /api/v1/**; it only exposes coverage the store already advertises.
@io.swagger.v3.oas.annotations.tags.Tag(name = "Envíos (público)", description = "Cobertura de envío del storefront, sin autenticación")
@RestController
@RequestMapping("/api/v1/shipping")
public class PublicShippingController {

    private final ShippingCoverageService shippingCoverageService;

    public PublicShippingController(ShippingCoverageService shippingCoverageService) {
        this.shippingCoverageService = shippingCoverageService;
    }

    @GetMapping("/coverage")
    public List<ShippingCoverageDto> coverage() {
        return shippingCoverageService.getCoverage();
    }
}
