package org.uvo.uvostore.service.geo;

import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.text.Normalizer;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Las regiones y comunas de Chile, con su código único territorial.
 *
 * <p>Antes, las zonas de envío se escribían a mano y se comparaban con lo que mandaba el checkout por texto
 * exacto: «Región Metropolitana» no era «Metropolitana», y la tienda solo conocía las comunas que el dueño había
 * escrito, así que no podía pedirle al comprador una comuna real. Con esta lista, el panel y el checkout eligen
 * de lo mismo y el cálculo compara nombres canónicos.
 *
 * <p>Los datos están en {@code geo/cl-regiones-comunas.json}, generado desde el servicio DPA del MOP (ver su
 * campo {@code source}). Se guarda el nombre, no el código, en zonas y órdenes: así lo ya guardado sigue siendo
 * legible, y {@link #resolveRegion}/{@link #resolveCommune} aceptan las variantes con que se escribía a mano.
 */
@Component
public class GeoCatalog {

    public record Commune(String code, String name) {
    }

    public record Region(String code, String name, List<Commune> communes) {
    }

    private record CatalogFile(String source, String retrievedAt, List<Region> regions) {
    }

    static final String RESOURCE = "geo/cl-regiones-comunas.json";

    // Normalizados (ver normalize) y ya sin el prefijo «región de…». Solo hacen falta los que no salen del
    // nombre oficial: «Valparaíso» o «Región de Valparaíso» ya coinciden solos.
    private static final Map<String, String> REGION_ALIASES = Map.ofEntries(
            Map.entry("arica", "15"),
            Map.entry("rm", "13"),
            Map.entry("metropolitana", "13"),
            Map.entry("santiago metropolitana", "13"),
            Map.entry("o higgins", "06"),
            Map.entry("ohiggins", "06"),
            Map.entry("libertador bernardo o higgins", "06"),
            Map.entry("bio bio", "08"),
            Map.entry("araucania", "09"),
            Map.entry("aysen", "11"),
            Map.entry("aisen", "11"),
            Map.entry("magallanes", "12"));

    // Grafías habituales que el catálogo oficial escribe de otra forma.
    private static final Map<String, String> COMMUNE_ALIASES = Map.ofEntries(
            Map.entry("coyhaique", "coihaique"),
            Map.entry("aysen", "aisen"),
            Map.entry("puerto aysen", "aisen"),
            Map.entry("puerto aisen", "aisen"),
            Map.entry("la calera", "calera"),
            Map.entry("llay llay", "llaillay"),
            Map.entry("til til", "tiltil"),
            Map.entry("marchigue", "marchihue"),
            Map.entry("rapa nui", "isla de pascua"),
            Map.entry("san pedro de melipilla", "san pedro"));

    private final List<Region> regions;
    private final Map<String, Region> regionsByKey = new HashMap<>();
    private final Map<String, Map<String, String>> communesByRegion = new HashMap<>();

    public GeoCatalog() {
        this(load());
    }

    GeoCatalog(List<Region> regions) {
        this.regions = List.copyOf(regions);
        for (Region region : this.regions) {
            regionsByKey.put(region.code(), region);
            regionsByKey.put(regionKey(region.name()), region);
            Map<String, String> communes = new LinkedHashMap<>();
            for (Commune commune : region.communes()) {
                communes.put(normalize(commune.name()), commune.name());
            }
            communesByRegion.put(region.name(), communes);
        }
        REGION_ALIASES.forEach((alias, code) -> regionsByKey.put(alias, regionsByKey.get(code)));
    }

    /** De norte a sur, cada una con sus comunas por código. */
    public List<Region> regions() {
        return regions;
    }

    /** Los nombres de las comunas de una región canónica; vacío si no lo es. */
    public List<String> communesOf(String region) {
        Map<String, String> communes = communesByRegion.get(region);
        return communes == null ? List.of() : List.copyOf(communes.values());
    }

    /** El nombre canónico de la región, sin importar tildes, mayúsculas ni el prefijo «Región de». */
    public Optional<String> resolveRegion(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(regionsByKey.get(regionKey(text))).map(Region::name);
    }

    /** El nombre canónico de la comuna dentro de esa región; vacío si la región no existe o no la tiene. */
    public Optional<String> resolveCommune(String region, String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        return resolveRegion(region).map(communesByRegion::get).map(communes -> {
            String key = normalize(text);
            return communes.get(COMMUNE_ALIASES.getOrDefault(key, key));
        });
    }

    private static String regionKey(String text) {
        String key = normalize(text);
        for (String prefix : List.of("region de la ", "region del ", "region de ", "region ")) {
            if (key.startsWith(prefix)) {
                return key.substring(prefix.length());
            }
        }
        return key;
    }

    // «Ñuñoa» y «nunoa» dan lo mismo: la ñ se descompone en n más la tilde, que se descarta con los acentos.
    static String normalize(String text) {
        String decomposed = Normalizer.normalize(text, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return decomposed.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ").trim();
    }

    private static List<Region> load() {
        try (InputStream in = GeoCatalog.class.getClassLoader().getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("No se encontró " + RESOURCE + " en el classpath");
            }
            return JsonMapper.builder().build().readValue(in, CatalogFile.class).regions();
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo leer " + RESOURCE, e);
        }
    }
}
