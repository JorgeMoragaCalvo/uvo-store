package org.uvo.uvostore.service.pos;

import java.math.BigDecimal;
import java.util.Map;

/**
 * POS-5. Una variante de un producto del POS (una talla, un color), tal como llega en una entrada de catálogo.
 *
 * <p>{@code stock} solo se usa al crear la variante: después el stock entra por {@code stock.updated} y por la
 * conciliación, condicionado al valor visto (F13), igual que el de un producto simple. {@code attributes} va del
 * nombre del atributo a su valor ({@code {"Talla": "M", "Color": "Azul"}}), y es lo que la tienda usa para elegir
 * la variante. Las reglas que tiene que cumplir la lista están en {@link PosVariants#validate}.
 */
public record PosVariantFields(Long variantId, String sku, BigDecimal price, Integer stock,
                               Map<String, String> attributes) {
}
