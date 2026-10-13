package org.uvo.uvostore.controller.admin.shipping;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.uvo.uvostore.entity.shipping.enums.ShippingCarrier;
import org.uvo.uvostore.service.shipping.carrier.ShippingCarrierPolicy;

import java.util.List;

/**
 * PROD-12. Qué transportistas puede configurar esta instalación.
 *
 * <p>Existe para que el panel ofrezca exactamente lo disponible en vez del enum completo: su desplegable
 * listaba «Chilexpress» y «Correos de Chile» como dos literales escritos a mano, y Correos no está
 * implementado. Un control que deja elegir algo que luego no funciona es peor que su ausencia.
 *
 * <p>Controlador aparte y no un {@code @GetMapping("/carriers")} dentro de
 * {@link AdminShippingMethodController}: ahí convive con un {@code @GetMapping("/{id}")} y, aunque Spring
 * da precedencia al literal sobre la plantilla, quien lea el archivo tiene que saberlo para estar seguro.
 * Una ruta propia no obliga a saberlo.
 */
@io.swagger.v3.oas.annotations.tags.Tag(name = "Transportistas (admin)", description = "Transportistas habilitados en esta instalación, JWT bearer con rol ADMIN")
@io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/api/admin/shipping/carriers")
public class AdminShippingCarrierController {

    private final ShippingCarrierPolicy carrierPolicy;

    public AdminShippingCarrierController(ShippingCarrierPolicy carrierPolicy) {
        this.carrierPolicy = carrierPolicy;
    }

    // Mismo permiso que ver los métodos de envío: es un dato para rellenar ese formulario, no una
    // capacidad aparte.
    @GetMapping
    @PreAuthorize("hasAuthority('shipping.view')")
    public List<String> available() {
        return carrierPolicy.available().stream().map(ShippingCarrier::name).toList();
    }
}
