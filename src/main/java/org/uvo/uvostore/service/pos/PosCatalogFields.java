package org.uvo.uvostore.service.pos;

import java.math.BigDecimal;
import java.util.List;

/**
 * POS-3. Los campos de catálogo que trae una entrada del POS. Un {@code null} es "no viene": no se escribe.
 * Que se escriba o no lo que sí viene lo deciden los flags del mapeo ({@link PosCatalogWriter}).
 *
 * <p>POS-5. {@code variants} es el estado completo de las variantes del producto en el POS, o {@code null} si la
 * entrada no las trae. Quien la construye ya la validó ({@link PosVariants#validate}).
 */
public record PosCatalogFields(String name, String description, BigDecimal price, List<PosVariantFields> variants) {

    /** Un producto sin variantes, como antes de POS-5. */
    public PosCatalogFields(String name, String description, BigDecimal price) {
        this(name, description, price, null);
    }
}
