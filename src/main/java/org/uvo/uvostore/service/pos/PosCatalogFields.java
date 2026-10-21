package org.uvo.uvostore.service.pos;

import java.math.BigDecimal;

/**
 * POS-3. Los campos de catálogo que trae una entrada del POS. Un {@code null} es "no viene": no se escribe.
 * Que se escriba o no lo que sí viene lo deciden los flags del mapeo ({@link PosCatalogWriter}).
 */
public record PosCatalogFields(String name, String description, BigDecimal price) {
}
