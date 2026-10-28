package org.uvo.uvostore.service.geo;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * El catálogo de regiones y comunas. Un nombre mal escrito en el archivo deja a una comuna sin envío, así que se
 * comprueban los totales oficiales región por región, no solo el total.
 */
class GeoCatalogTest {

    private final GeoCatalog catalog = new GeoCatalog();

    @Test
    @DisplayName("Tiene las 16 regiones de norte a sur con sus 346 comunas")
    void hasTheOfficialRegionsAndCommuneCounts() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        catalog.regions().forEach(r -> counts.put(r.code(), r.communes().size()));

        assertThat(counts).containsExactly(
                Map.entry("15", 4), Map.entry("01", 7), Map.entry("02", 9), Map.entry("03", 9),
                Map.entry("04", 15), Map.entry("05", 38), Map.entry("13", 52), Map.entry("06", 33),
                Map.entry("07", 30), Map.entry("16", 21), Map.entry("08", 33), Map.entry("09", 32),
                Map.entry("14", 12), Map.entry("10", 30), Map.entry("11", 10), Map.entry("12", 11));
        assertThat(counts.values().stream().mapToInt(Integer::intValue).sum()).isEqualTo(346);
    }

    @Test
    @DisplayName("Cada comuna tiene un código de su región y no se repite")
    void communeCodesBelongToTheirRegionAndAreUnique() {
        Set<String> codes = new HashSet<>();
        for (GeoCatalog.Region region : catalog.regions()) {
            Set<String> names = new HashSet<>();
            for (GeoCatalog.Commune commune : region.communes()) {
                assertThat(commune.code()).startsWith(region.code()).hasSize(5);
                assertThat(codes.add(commune.code())).as("código repetido %s", commune.code()).isTrue();
                assertThat(names.add(commune.name())).as("comuna repetida %s", commune.name()).isTrue();
            }
        }
    }

    @Test
    @DisplayName("Reconoce las regiones como se escriben a mano")
    void resolvesRegionVariants() {
        for (String text : List.of("RM", "Región Metropolitana", "metropolitana", "Metropolitana de Santiago",
                "Región Metropolitana de Santiago", "13")) {
            assertThat(catalog.resolveRegion(text)).as(text).contains("Metropolitana de Santiago");
        }
        assertThat(catalog.resolveRegion("Region de Valparaiso")).contains("Valparaíso");
        assertThat(catalog.resolveRegion("Bio Bio")).contains("Biobío");
        assertThat(catalog.resolveRegion("Región del Biobío")).contains("Biobío");
        assertThat(catalog.resolveRegion("O'Higgins")).contains("Libertador General Bernardo O'Higgins");
        assertThat(catalog.resolveRegion("Araucanía")).contains("La Araucanía");
        assertThat(catalog.resolveRegion("Región de Ñuble")).contains("Ñuble");
        assertThat(catalog.resolveRegion("Aysén")).contains("Aysén del General Carlos Ibáñez del Campo");
        assertThat(catalog.resolveRegion("Magallanes")).contains("Magallanes y de la Antártica Chilena");
    }

    @Test
    @DisplayName("Reconoce las comunas dentro de su región, sin importar tildes ni mayúsculas")
    void resolvesCommunesWithinTheirRegion() {
        assertThat(catalog.resolveCommune("RM", "Nunoa")).contains("Ñuñoa");
        assertThat(catalog.resolveCommune("RM", "santiago")).contains("Santiago");
        assertThat(catalog.resolveCommune("Valparaíso", "vina del mar")).contains("Viña del Mar");
        assertThat(catalog.resolveCommune("Aysén", "Coyhaique")).contains("Coihaique");
        assertThat(catalog.resolveCommune("Magallanes", "Antártica")).contains("Antártica");
    }

    @Test
    @DisplayName("Lo que no existe, o es de otra región, no se reconoce")
    void rejectsUnknownNames() {
        assertThat(catalog.resolveRegion("Región de Narnia")).isEmpty();
        assertThat(catalog.resolveRegion("")).isEmpty();
        assertThat(catalog.resolveRegion(null)).isEmpty();
        assertThat(catalog.resolveCommune("RM", "Viña del Mar")).isEmpty();
        assertThat(catalog.resolveCommune("Narnia", "Santiago")).isEmpty();
        assertThat(catalog.resolveCommune("RM", null)).isEmpty();
    }

    @Test
    @DisplayName("Lista las comunas de una región canónica")
    void listsTheCommunesOfARegion() {
        assertThat(catalog.communesOf("Metropolitana de Santiago")).hasSize(52).contains("Puente Alto", "Ñuñoa");
        assertThat(catalog.communesOf("Narnia")).isEmpty();
    }
}
