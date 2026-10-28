package org.uvo.uvostore.controller.geo;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.uvo.uvostore.service.geo.GeoCatalog;

import java.util.List;

// El catálogo de regiones y comunas con que el panel arma las zonas de envío. Público como el resto de
// /api/v1/**: son datos oficiales, iguales para todas las tiendas.
@io.swagger.v3.oas.annotations.tags.Tag(name = "Geografía (público)", description = "Regiones y comunas de Chile")
@RestController
@RequestMapping("/api/v1/geo")
public class PublicGeoController {

    private final GeoCatalog geoCatalog;

    public PublicGeoController(GeoCatalog geoCatalog) {
        this.geoCatalog = geoCatalog;
    }

    @GetMapping("/regions")
    public List<GeoCatalog.Region> regions() {
        return geoCatalog.regions();
    }
}
