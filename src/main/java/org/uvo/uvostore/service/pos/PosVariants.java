package org.uvo.uvostore.service.pos;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * POS-5. Las reglas que tiene que cumplir la lista de variantes de una entrada del POS, las mismas para la
 * sincronización, {@code product.updated} y la conciliación.
 *
 * <p>La tienda elige la variante pidiendo un valor de <b>cada</b> atributo y buscando la variante que coincide en
 * todos ({@code ProductDetail.tsx}). Una variante con otros nombres de atributo, o dos con la misma combinación,
 * no se podrían elegir nunca: por eso se rechazan en la entrada y no se descubren comprando. Los nombres y los
 * valores se comparan por su slug, que es como se guardan ({@code Attribute.slug}, {@code AttributeValue.slug}):
 * "Talla" y "talla" son el mismo atributo.
 */
public final class PosVariants {

    private PosVariants() {
    }

    /** El motivo por el que la lista no sirve, o {@code null} si sirve. */
    public static String validate(List<PosVariantFields> variants) {
        if (variants == null || variants.isEmpty()) {
            return "La lista de variantes vino vacía o no es una lista.";
        }
        Set<Long> ids = new HashSet<>();
        Set<String> skus = new HashSet<>();
        Set<Map<String, String>> combinations = new HashSet<>();
        Set<String> attributeNames = null;
        for (PosVariantFields variant : variants) {
            if (variant.variantId() == null || isBlank(variant.sku()) || variant.price() == null
                    || variant.attributes() == null || variant.attributes().isEmpty()) {
                return "Cada variante tiene que traer variantId, sku, price y attributes.";
            }
            if (variant.price().signum() < 0 || (variant.stock() != null && variant.stock() < 0)) {
                return "La variante %d trae un precio o un stock negativo.".formatted(variant.variantId());
            }
            if (!ids.add(variant.variantId())) {
                return "La variante %d viene repetida.".formatted(variant.variantId());
            }
            if (!skus.add(variant.sku().trim())) {
                return "El SKU %s viene en dos variantes.".formatted(variant.sku().trim());
            }
            Map<String, String> combination = slugs(variant.attributes());
            if (combination == null) {
                return ("La variante %d trae un atributo sin nombre o sin valor, o el mismo atributo dos veces.")
                        .formatted(variant.variantId());
            }
            if (attributeNames == null) {
                attributeNames = combination.keySet();
            } else if (!attributeNames.equals(combination.keySet())) {
                return ("Todas las variantes tienen que traer los mismos atributos: la variante %d trae %s y la "
                        + "primera trae %s.").formatted(variant.variantId(), combination.keySet(), attributeNames);
            }
            if (!combinations.add(combination)) {
                return "Dos variantes traen la misma combinación de atributos: %s.".formatted(variant.attributes());
            }
        }
        return null;
    }

    /**
     * Lee la lista de variantes de un cuerpo JSON ya convertido en mapas, campo a campo y sin lanzar: un campo que
     * no se entiende queda en {@code null}, y es {@link #validate} el que decide. {@code null} si no viene.
     *
     * @param idKey    cómo se llama el id de la variante en esa superficie ({@code variantId} o {@code variant_id})
     * @param stockKey cómo se llama su stock ({@code stock} o {@code stock_web})
     */
    public static List<PosVariantFields> fromJson(Object raw, String idKey, String stockKey) {
        if (raw == null) {
            return null;
        }
        if (!(raw instanceof List<?> entries)) {
            return List.of();
        }
        List<PosVariantFields> variants = new ArrayList<>();
        for (Object entry : entries) {
            Map<?, ?> variant = entry instanceof Map<?, ?> map ? map : Map.of();
            variants.add(new PosVariantFields(
                    PosCatalogSnapshot.asLong(variant.get(idKey)),
                    PosCatalogSnapshot.asText(variant.get("sku")),
                    PosCatalogSnapshot.asDecimal(variant.get("price")),
                    PosCatalogSnapshot.asInt(variant.get(stockKey)),
                    attributes(variant.get("attributes"))));
        }
        return variants;
    }

    /** El slug de un nombre o un valor de atributo: es la clave con la que se guarda. */
    static String slug(String value) {
        return PosCatalogWriter.slugify(value);
    }

    // De nombre a valor, por slug, y ordenado: así dos combinaciones iguales escritas en otro orden son iguales.
    // null si un nombre o un valor queda vacío, o si dos nombres dan el mismo slug ("Talla" y "talla").
    private static Map<String, String> slugs(Map<String, String> attributes) {
        Map<String, String> result = new TreeMap<>();
        for (Map.Entry<String, String> attribute : attributes.entrySet()) {
            if (isBlank(attribute.getKey()) || isBlank(attribute.getValue())) {
                return null;
            }
            String name = slug(attribute.getKey());
            String value = slug(attribute.getValue());
            if (name.isEmpty() || value.isEmpty() || result.put(name, value) != null) {
                return null;
            }
        }
        return result;
    }

    // LinkedHashMap: conserva el orden en que el POS los manda, que es el que verá la tienda.
    private static Map<String, String> attributes(Object raw) {
        if (!(raw instanceof Map<?, ?> map)) {
            return null;
        }
        Map<String, String> attributes = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            attributes.put(PosCatalogSnapshot.asText(entry.getKey()), PosCatalogSnapshot.asText(entry.getValue()));
        }
        return attributes;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /** El precio mínimo de una lista ya validada: el del padre de un producto variable. */
    static BigDecimal minPrice(List<PosVariantFields> variants) {
        return variants.stream().map(PosVariantFields::price).min(BigDecimal::compareTo).orElse(BigDecimal.ZERO);
    }
}
